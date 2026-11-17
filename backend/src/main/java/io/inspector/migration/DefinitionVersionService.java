package io.inspector.migration;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.audit.ProtectedDefinition;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessDefinitionPage;
import io.inspector.client.ProcessDefinitionRecord;
import io.inspector.client.ProcessInstancePage;
import io.inspector.client.ProcessInstanceQuery;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

/**
 * The definition-versions on-ramp (the migration cohort-visibility read). Lists every deployed
 * version of a process key with its RUNNING instance count — count-only, Stage-0 discipline
 * ({@code size=1} runtime queries, never a row fetch). Read-only: no capability gate (version
 * visibility is useful on any engine), no audit.
 */
@Service
public class DefinitionVersionService {

    private static final Logger log = LoggerFactory.getLogger(DefinitionVersionService.class);

    // The on-ramp shows the newest N versions with running counts — one count query per version,
    // so this is also the fan-out bound (a pathologically-redeployed key never triggers hundreds
    // of count calls; older versions are summarized by `complete=false` + `totalVersions`).
    private static final int VERSION_CAP = 50;

    private final EngineRegistry registry;
    private final ProcessApiClient client;
    private final ProtectedDefinitionRepository protectedDefinitions;

    public DefinitionVersionService(
            EngineRegistry registry, ProcessApiClient client, ProtectedDefinitionRepository protectedDefinitions) {
        this.registry = registry;
        this.client = client;
        this.protectedDefinitions = protectedDefinitions;
    }

    public DefinitionVersionsResponse versions(String engineId, String key) {
        EngineConfig engine = registry.require(engineId);
        ProcessDefinitionPage newest;
        try {
            // The newest VERSION_CAP versions in a STABLE desc order (one page suffices at the cap).
            newest = client.listProcessDefinitionVersionsDesc(engine, CallPriority.INTERACTIVE, key, 0, VERSION_CAP);
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "engine-unreachable",
                    "Engine '" + engine.id() + "' did not answer the version query.");
        }
        List<ProcessDefinitionRecord> definitions = newest != null ? newest.dataOrEmpty() : List.of();
        if (definitions.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "unknown-definition-key",
                    "No deployed version of process key '" + key + "' exists on '" + engine.id() + "'.");
        }
        int totalVersions = (int) newest.total();
        // Desc order → the first row is the latest.
        int latest = definitions.get(0).version();

        List<DefinitionVersionsResponse.DefinitionVersion> versions = new ArrayList<>();
        try {
            for (ProcessDefinitionRecord d : definitions) {
                String id = String.valueOf(d.id());
                int version = d.version();
                versions.add(new DefinitionVersionsResponse.DefinitionVersion(
                        id, version, d.name(), d.deploymentId(), runningInstanceCount(engine, id), version == latest));
            }
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "engine-unreachable",
                    "Engine '" + engine.id() + "' did not answer a version instance-count query.");
        }
        versions.sort(Comparator.comparingInt(DefinitionVersionsResponse.DefinitionVersion::version)
                .reversed());
        boolean complete = totalVersions <= versions.size();
        Protection protection = protectionOf(engineId, key);
        return new DefinitionVersionsResponse(
                engineId,
                key,
                latest,
                totalVersions,
                complete,
                versions,
                protection.isProtected(),
                protection.reason());
    }

    /**
     * #172 Copilot review: this is otherwise an ENGINE-only read (no capability gate, no audit) —
     * a Postgres outage must degrade to unknown, not take the whole on-ramp page down. Mirrors
     * {@code InstanceDetailService#protectionOf}/{@code SearchService#markProtected}; the
     * execution-time {@code ProtectionGuard} still refuses fail-closed regardless of what this
     * read shows.
     */
    private Protection protectionOf(String engineId, String key) {
        try {
            return protectedDefinitions
                    .findById(new ProtectedDefinition.Key(engineId, key))
                    .map(p -> new Protection(true, p.getReason()))
                    .orElseGet(() -> new Protection(false, null));
        } catch (RuntimeException e) {
            log.warn(
                    "protected-definition lookup unavailable — versions carries protectedDefinition=null: {}",
                    e.toString());
            return new Protection(null, null);
        }
    }

    private record Protection(Boolean isProtected, String reason) {}

    /** RUNNING instances on ONE definition version — count-only ({@code size=1}, read {@code total}). */
    private long runningInstanceCount(EngineConfig engine, String definitionId) {
        ProcessInstanceQuery body = ProcessInstanceQuery.builder()
                .processDefinitionId(definitionId)
                .size(1)
                .build();
        ProcessInstancePage page = client.queryRuntimeProcessInstances(engine, CallPriority.INTERACTIVE, body);
        return page != null ? page.total() : 0;
    }
}
