package io.inspector.catalog;

import io.inspector.action.GuardRefusedException;
import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.catalog.DeploymentCatalogResponse.CatalogDeployment;
import io.inspector.catalog.DeploymentDetailResponse.ContainedDefinition;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.registry.EngineRegistry;
import jakarta.annotation.PreDestroy;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Deployment browser and the tier-3 delete verb. Cascade defaults off. A live-instance count
 * that cannot be proved empty refuses the delete. The count is taken again at execute time,
 * after the audit row is opened and before the engine is touched.
 */
@Service
public class DeploymentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentCatalogService.class);
    static final int LIST_CAP = 50;

    private final EngineRegistry registry;
    private final ProcessApiClient processes;
    private final AuditService audit;
    private final ExecutorService fanout = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
    private final Semaphore engineSlots;

    public DeploymentCatalogService(
            EngineRegistry registry, ProcessApiClient processes, AuditService audit, InspectorProperties props) {
        this.registry = registry;
        this.processes = processes;
        this.audit = audit;
        int fanoutN = props.fanoutParallelism() != null ? props.fanoutParallelism() : 8;
        this.engineSlots = new Semaphore(fanoutN);
    }

    public DeploymentCatalogResponse list(Set<String> engineIds, String name, Set<String> readableEngineIds) {
        String nameLike = nameLike(name);
        List<EngineConfig> targets = resolveTargets(engineIds, readableEngineIds);
        Map<String, EngineResult> perEngine = new ConcurrentHashMap<>();
        markExcluded(engineIds, targets, perEngine);
        Map<EngineConfig, CompletableFuture<List<CatalogDeployment>>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            futures.put(
                    engine,
                    CompletableFuture.supplyAsync(
                            () -> {
                                engineSlots.acquireUninterruptibly();
                                try {
                                    return oneEngine(engine, nameLike);
                                } finally {
                                    engineSlots.release();
                                }
                            },
                            fanout));
        }
        List<CatalogDeployment> rows = new ArrayList<>();
        for (Map.Entry<EngineConfig, CompletableFuture<List<CatalogDeployment>>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            long budgetMs = engine.timeoutsOrDefault().read() * 4L + 2000;
            try {
                List<CatalogDeployment> slice = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                rows.addAll(slice);
                perEngine.put(engine.id(), EngineResult.success(slice.size(), slice.size()));
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                perEngine.put(engine.id(), EngineResult.failure("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Engine {} deployment catalog failed: {}", engine.id(), cause.toString());
                perEngine.put(engine.id(), EngineResult.failure(cause.getMessage()));
            }
        }
        rows.sort(
                Comparator.comparing(CatalogDeployment::deploymentTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CatalogDeployment::engineId));
        return new DeploymentCatalogResponse(rows, new HashMap<>(perEngine));
    }

    public DeploymentDetailResponse detail(String engineId, String deploymentId) {
        EngineConfig engine = registry.require(engineId);
        Map<String, Object> deployment = processes.getDeployment(engine, CallPriority.INTERACTIVE, deploymentId);
        if (deployment == null) {
            throw notFound(engineId, deploymentId);
        }
        FlowablePage definitions = processes.listProcessDefinitions(
                engine, CallPriority.INTERACTIVE, null, null, null, deploymentId, 0, LIST_CAP);
        boolean bounded = definitions.total() > definitions.dataOrEmpty().size();
        List<ContainedDefinition> contained = new ArrayList<>();
        long running = 0;
        for (Map<String, Object> row : definitions.dataOrEmpty()) {
            Long count = null;
            try {
                count = runningOn(engine, str(row, "id"));
                running += count;
            } catch (RuntimeException e) {
                bounded = true;
            }
            contained.add(new ContainedDefinition(
                    str(row, "id"), str(row, "key"), str(row, "name"), number(row.get("version")), count));
        }
        List<String> resources = new ArrayList<>();
        try {
            FlowablePage page = processes.listDeploymentResources(engine, CallPriority.INTERACTIVE, deploymentId);
            for (Map<String, Object> row : page.dataOrEmpty()) {
                String name = str(row, "id");
                if (name == null) {
                    name = str(row, "name");
                }
                if (name != null) {
                    resources.add(name);
                }
            }
        } catch (RuntimeException e) {
            log.warn("deployment resources unreadable on {}: {}", engineId, e.toString());
        }
        return new DeploymentDetailResponse(
                engineId,
                deploymentId,
                str(deployment, "name"),
                str(deployment, "category"),
                str(deployment, "tenantId") != null ? str(deployment, "tenantId") : engine.tenantId(),
                str(deployment, "deploymentTime"),
                running,
                bounded,
                contained,
                resources);
    }

    public DeleteDeploymentResponse delete(
            String engineId, String deploymentId, DeleteDeploymentRequest request, Authentication auth) {
        EngineConfig engine = registry.require(engineId);
        requireWritable(engine);
        String reason = normalizedReason(request != null ? request.reason() : null);
        boolean cascade = request != null && Boolean.TRUE.equals(request.cascade());
        if (engine.environment() == EngineEnvironment.PROD) {
            String token = request != null ? request.confirmToken() : null;
            if (!deploymentId.equals(token)) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "confirm-token-mismatch",
                        "Destructive action on a PROD engine: type the deployment id (\"" + deploymentId + "\").");
            }
        }
        if (processes.getDeployment(engine, CallPriority.INTERACTIVE, deploymentId) == null) {
            throw notFound(engineId, deploymentId);
        }
        Live live = liveCount(engine, deploymentId);
        if (live.bounded()) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "live-count-unknown",
                    "Refusing to delete deployment " + deploymentId
                            + " because its running-instance count could not be proved.");
        }
        if (live.running() > 0 && !cascade) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "deployment-has-live-instances",
                    "Deployment " + deploymentId + " still has " + live.running()
                            + " running instance(s). Cascade stays off unless you set it explicitly. Nothing was deleted.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "delete-deployment/v1");
        payload.put("deploymentId", deploymentId);
        payload.put("cascade", cascade);
        payload.put("runningCount", live.running());
        String ticket = request != null ? blankToNull(request.ticketId()) : null;
        AuditEntry entry = audit.beginPending(
                actor(auth), engineId, engine.tenantId(), null, "delete-deployment", reason, ticket, payload);
        try {
            processes.deleteDeployment(engine, CallPriority.INTERACTIVE, deploymentId, cascade);
            audit.close(entry, AuditOutcome.ok, 204, "deleted", true);
            return new DeleteDeploymentResponse(
                    "ok",
                    cascade
                            ? "Deployment " + deploymentId + " deleted with cascade. Irreversible."
                            : "Deployment " + deploymentId + " deleted. It had no running instances. Irreversible.",
                    live.running(),
                    cascade);
        } catch (RestClientResponseException e) {
            audit.close(entry, AuditOutcome.failed, e.getStatusCode().value(), e.getMessage(), false);
            throw new GuardRefusedException(
                    HttpStatus.valueOf(e.getStatusCode().value()),
                    "engine-rejected",
                    "The engine rejected the delete: " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            boolean sent = !notDispatched(e);
            audit.close(entry, sent ? AuditOutcome.unknown : AuditOutcome.failed, null, e.getMessage(), false);
            if (!sent) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_GATEWAY,
                        "engine-unreachable",
                        "Engine '" + engineId + "' did not accept the delete. Nothing was confirmed.");
            }
            return new DeleteDeploymentResponse(
                    "unknown",
                    "The delete was dispatched but the engine did not answer. Do not retry blindly — open the deployment and see if it is gone.",
                    live.running(),
                    cascade);
        }
    }

    private Live liveCount(EngineConfig engine, String deploymentId) {
        FlowablePage definitions = processes.listProcessDefinitions(
                engine, CallPriority.INTERACTIVE, null, null, null, deploymentId, 0, LIST_CAP);
        if (definitions.total() > definitions.dataOrEmpty().size()) {
            return new Live(0, true);
        }
        long running = 0;
        for (Map<String, Object> row : definitions.dataOrEmpty()) {
            try {
                running += runningOn(engine, str(row, "id"));
            } catch (RuntimeException e) {
                return new Live(running, true);
            }
        }
        return new Live(running, false);
    }

    private long runningOn(EngineConfig engine, String definitionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processDefinitionId", definitionId);
        body.put("size", 1);
        FlowablePage page = processes.queryRuntimeProcessInstances(engine, CallPriority.INTERACTIVE, body);
        return page != null ? page.total() : 0;
    }

    private List<CatalogDeployment> oneEngine(EngineConfig engine, String nameLike) {
        FlowablePage page = processes.listDeployments(engine, CallPriority.INTERACTIVE, nameLike, 0, LIST_CAP);
        List<CatalogDeployment> rows = new ArrayList<>();
        for (Map<String, Object> row : page.dataOrEmpty()) {
            rows.add(new CatalogDeployment(
                    engine.id(),
                    str(row, "id"),
                    str(row, "name"),
                    str(row, "category"),
                    str(row, "tenantId") != null ? str(row, "tenantId") : engine.tenantId(),
                    str(row, "deploymentTime")));
        }
        return rows;
    }

    private void requireWritable(EngineConfig engine) {
        if (!engine.enabled()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "engine-disabled",
                    "Engine '" + engine.id() + "' is disabled in the registry.");
        }
        if (engine.modeOrDefault() == EngineMode.READ_ONLY) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "engine-read-only",
                    "Engine '" + engine.id() + "' is registered read-only — delete-deployment is rejected.");
        }
    }

    private static String normalizedReason(String reason) {
        String value = blankToNull(reason);
        if (value == null || value.length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "reason-required",
                    "Delete deployment requires a reason of at least 10 characters.");
        }
        return value;
    }

    private List<EngineConfig> resolveTargets(Set<String> engineIds, Set<String> readableEngineIds) {
        List<EngineConfig> base = (engineIds == null || engineIds.isEmpty())
                ? registry.all()
                : registry.all().stream()
                        .filter(e -> engineIds.contains(e.id()))
                        .toList();
        if (readableEngineIds == null) {
            return base;
        }
        return base.stream().filter(e -> readableEngineIds.contains(e.id())).toList();
    }

    private void markExcluded(
            Set<String> requestedIds, List<EngineConfig> targets, Map<String, EngineResult> perEngine) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return;
        }
        Set<String> resolved = targets.stream().map(EngineConfig::id).collect(Collectors.toSet());
        for (String requested : requestedIds) {
            if (requested == null || requested.isBlank() || resolved.contains(requested)) {
                continue;
            }
            Optional<EngineConfig> row = registry.resolve(requested);
            String why = row.isPresent() && row.get().enabled()
                    ? "the engine \"" + requested + "\" is outside your access scope — excluded from this catalog"
                    : "the engine \"" + requested + "\" is not in this catalog";
            perEngine.put(requested, EngineResult.failure(why));
        }
    }

    private static boolean notDispatched(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String actor(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "unknown";
        }
        return auth.getName();
    }

    private static GuardRefusedException notFound(String engineId, String deploymentId) {
        return new GuardRefusedException(
                HttpStatus.NOT_FOUND,
                "deployment-not-found",
                "Deployment " + deploymentId + " does not exist on '" + engineId + "'.");
    }

    private static String nameLike(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.indexOf('%') >= 0 ? trimmed : "%" + trimmed + "%";
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @PreDestroy
    void shutdown() {
        fanout.shutdownNow();
    }

    private record Live(long running, boolean bounded) {}
}
