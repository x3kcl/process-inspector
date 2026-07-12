package io.inspector.registry;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineHealth.JobLanes;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The Stage 0 health probe: on boot and every 30s, fan out over all registered engines
 * on virtual threads and aggregate per engine — reachability/version, capability flags
 * (ARCHITECTURE §2.5), the four job-lane counts and the two executor-starvation alarms.
 *
 * All engine calls are size=1/count-only (Stage 0 never reuses the grid-search plan) and
 * run through the client's per-engine circuit breakers; an open breaker degrades that
 * engine's entry, never the whole cycle.
 */
@Service
public class EngineHealthService {

    private static final Logger log = LoggerFactory.getLogger(EngineHealthService.class);

    /** Flowable serializes dates as ISO-8601 with non-colon offsets, e.g. 2026-07-06T10:00:00.000+0000. */
    private static final DateTimeFormatter FLOWABLE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;
    private final Clock clock;
    private final ExecutorService probePool = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();

    public EngineHealthService(EngineRegistry registry, ProcessApiClient flowable, Clock clock) {
        this.registry = registry;
        this.flowable = flowable;
        this.clock = clock;
    }

    /** fixedDelay is measured from cycle completion, so slow probes never overlap. */
    @Scheduled(initialDelay = 2_000, fixedDelay = 30_000)
    public void probeAll() {
        Map<String, Future<EngineHealth>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : registry.all()) {
            futures.put(engine.id(), probePool.submit(() -> probeOne(engine)));
        }
        futures.forEach((engineId, future) -> {
            try {
                registry.updateHealth(engineId, future.get());
            } catch (Exception ex) {
                log.warn("Health probe for {} died unexpectedly: {}", engineId, ex.toString());
                registry.updateHealth(engineId, EngineHealth.unreachable(rootMessage(ex), clock.millis()));
            }
        });
    }

    /**
     * Re-probe ONE engine now (v2 Registry CRUD S3 reload seam): after a registry edit commits, the
     * reload listener calls this so the health strip reflects the new endpoint without waiting up to
     * 30s for the next scheduled cycle. A read-only probe (version + lanes), never a mutating call.
     * Silently no-ops if the id left the registry (disabled→removed) between commit and reload.
     */
    public void reprobe(String engineId) {
        EngineConfig engine = registry.all().stream()
                .filter(e -> e.id().equals(engineId))
                .findFirst()
                .orElse(null);
        if (engine == null) {
            return; // not an enabled engine (disabled/removed) — nothing to probe
        }
        try {
            registry.updateHealth(engineId, probeOne(engine));
        } catch (Exception ex) {
            log.warn("Re-probe after registry change for {} failed: {}", engineId, ex.toString());
            registry.updateHealth(engineId, EngineHealth.unreachable(rootMessage(ex), clock.millis()));
        }
    }

    /** Package-private so tests can drive one deterministic probe without the schedule. */
    EngineHealth probeOne(EngineConfig engine) {
        String version;
        try {
            Map<String, Object> info = flowable.engineInfo(engine, CallPriority.INTERACTIVE);
            version = info != null && info.get("version") != null
                    ? info.get("version").toString()
                    : "unknown";
        } catch (CallNotPermittedException open) {
            return EngineHealth.unreachable("circuit open — engine shedding load", clock.millis());
        } catch (Exception ex) {
            log.debug("Health probe failed for {}: {}", engine.id(), ex.toString());
            return EngineHealth.unreachable(rootMessage(ex), clock.millis());
        }

        // Reachable. Later legs degrade the entry instead of blanking it.
        try {
            EngineCapabilities capabilities = EngineCapabilities.fromVersion(
                    version, flowable.probeActivityHistory(engine, CallPriority.INTERACTIVE));
            JobLanes lanes = new JobLanes(
                    flowable.countJobs(engine, CallPriority.INTERACTIVE, JobLaneKind.EXECUTABLE),
                    flowable.countJobs(engine, CallPriority.INTERACTIVE, JobLaneKind.TIMER),
                    flowable.countJobs(engine, CallPriority.INTERACTIVE, JobLaneKind.SUSPENDED),
                    flowable.countJobs(engine, CallPriority.INTERACTIVE, JobLaneKind.DEADLETTER));
            Long oldestAgeSec = oldestExecutableJobAgeSec(engine, lanes.executable());
            long overdueTimers = flowable.countOverdueTimers(
                    engine,
                    CallPriority.INTERACTIVE,
                    clock.instant().minusSeconds(engine.alarmsOrDefault().overdueTimerGraceSOrDefault()));
            return new EngineHealth(
                    true, version, null, clock.millis(), capabilities, lanes, oldestAgeSec, overdueTimers);
        } catch (Exception ex) {
            log.debug("Lane/capability probe degraded for {}: {}", engine.id(), ex.toString());
            return new EngineHealth(
                    true,
                    version,
                    "reachable, but lane/capability probe failed: " + rootMessage(ex),
                    clock.millis(),
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** Age of the oldest executable job (dueDate, falling back to createTime), floored at 0. */
    private Long oldestExecutableJobAgeSec(EngineConfig engine, long executableCount) {
        if (executableCount == 0) {
            return null;
        }
        Map<String, Object> job = flowable.oldestExecutableJob(engine, CallPriority.INTERACTIVE);
        if (job == null) {
            return null;
        }
        Instant reference = parseFlowableDate(job.get("dueDate"));
        if (reference == null) {
            reference = parseFlowableDate(job.get("createTime"));
        }
        if (reference == null) {
            return null;
        }
        return Math.max(0, Duration.between(reference, clock.instant()).toSeconds());
    }

    static Instant parseFlowableDate(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        try {
            return Instant.parse(text); // 2026-07-06T10:00:00Z
        } catch (Exception ignored) {
            /* fall through */
        }
        try {
            return OffsetDateTime.parse(text).toInstant(); // ...+02:00
        } catch (Exception ignored) {
            /* fall through */
        }
        try {
            return OffsetDateTime.parse(text, FLOWABLE_DATE).toInstant(); // ...+0000
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }

    @PreDestroy
    void shutdown() {
        probePool.shutdownNow();
    }
}
