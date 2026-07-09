package io.inspector.migration;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // The on-ramp shows the newest N versions with running counts — one count query per version,
    // so this is also the fan-out bound (a pathologically-redeployed key never triggers hundreds
    // of count calls; older versions are summarized by `complete=false` + `totalVersions`).
    private static final int VERSION_CAP = 50;

    private final EngineRegistry registry;
    private final FlowableEngineClient client;

    public DefinitionVersionService(EngineRegistry registry, FlowableEngineClient client) {
        this.registry = registry;
        this.client = client;
    }

    public DefinitionVersionsResponse versions(String engineId, String key) {
        EngineConfig engine = registry.require(engineId);
        FlowablePage newest;
        try {
            // The newest VERSION_CAP versions in a STABLE desc order (one page suffices at the cap).
            newest = client.listProcessDefinitionVersionsDesc(engine, key, 0, VERSION_CAP);
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "engine-unreachable",
                    "Engine '" + engine.id() + "' did not answer the version query.");
        }
        List<Map<String, Object>> definitions = newest != null ? newest.dataOrEmpty() : List.of();
        if (definitions.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "unknown-definition-key",
                    "No deployed version of process key '" + key + "' exists on '" + engine.id() + "'.");
        }
        int totalVersions = (int) newest.total();
        // Desc order → the first row is the latest.
        int latest = ((Number) definitions.get(0).get("version")).intValue();

        List<DefinitionVersionsResponse.DefinitionVersion> versions = new ArrayList<>();
        try {
            for (Map<String, Object> d : definitions) {
                String id = String.valueOf(d.get("id"));
                int version = ((Number) d.get("version")).intValue();
                versions.add(new DefinitionVersionsResponse.DefinitionVersion(
                        id,
                        version,
                        d.get("name") != null ? String.valueOf(d.get("name")) : null,
                        d.get("deploymentId") != null ? String.valueOf(d.get("deploymentId")) : null,
                        runningInstanceCount(engine, id),
                        version == latest));
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
        return new DefinitionVersionsResponse(engineId, key, latest, totalVersions, complete, versions);
    }

    /** RUNNING instances on ONE definition version — count-only ({@code size=1}, read {@code total}). */
    private long runningInstanceCount(EngineConfig engine, String definitionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processDefinitionId", definitionId);
        body.put("size", 1);
        FlowablePage page = client.queryRuntimeProcessInstances(engine, body);
        return page != null ? page.total() : 0;
    }
}
