package io.inspector.triage;

import io.inspector.dto.EngineDto;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.security.ReadScopeGate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * S2 read scoping for the triage dashboard (R-SAFE-17). The dashboard is served from a SHARED 20s
 * single-flight Caffeine cache — one fleet-wide snapshot for every caller (thundering-herd
 * protection during a P1) — and the background {@code SnapshotSampler} drives the same
 * aggregation. Scoping therefore MUST be a per-request RENDER-TIME projection applied POST-cache
 * (exactly like {@code ErrorGroupAckService.decorate} joins live ack state), NEVER a scope-aware
 * cache key (which would shatter single-flight) and NEVER inside the aggregation itself (the
 * sampler has no auth and would empty the shared snapshot).
 *
 * <p>Given the caller's readable engine set (from {@link ReadScopeGate}; {@code null} = enforcement
 * off = unrestricted, returned verbatim), it narrows every engine-keyed facet to readable engines
 * and recomputes the global roll-ups from the survivors. An {@link ErrorGroup} that touches no
 * readable engine is dropped; one that is only PARTIALLY in scope keeps its recomputed {@code total}
 * and filtered {@code countsByEngine} but has its {@code deadLetterCount}/{@code retryingCount}
 * NULLED — that split is a fleet-wide aggregate with no per-engine breakdown, so it cannot be
 * honestly recomputed for a slice (nulling is the least-dishonest choice; the UI renders "—").
 */
@Component
public class TriageScopeProjector {

    private final ReadScopeGate gate;

    public TriageScopeProjector(ReadScopeGate gate) {
        this.gate = gate;
    }

    public TriageDashboardResponse project(TriageDashboardResponse dashboard, Authentication auth) {
        Set<String> readable = gate.readableEngineIds(auth);
        if (readable == null) {
            return dashboard; // enforcement off — the legacy fleet-wide dashboard
        }

        List<EngineDto> engines = dashboard.engines().stream()
                .filter(e -> readable.contains(e.id()))
                .toList();

        Map<String, Map<String, Long>> statusCountsByEngine = new LinkedHashMap<>();
        dashboard.statusCountsByEngine().forEach((engineId, counts) -> {
            if (readable.contains(engineId)) {
                statusCountsByEngine.put(engineId, counts);
            }
        });

        // Global status counts are re-summed from the survivors — never carried over from the
        // fleet-wide roll-up (which would leak out-of-scope volume).
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCountsByEngine
                .values()
                .forEach(counts -> counts.forEach((status, n) -> statusCounts.merge(status, n, Long::sum)));

        Map<String, PerEngineTriage> perEngine = new LinkedHashMap<>();
        dashboard.perEngine().forEach((engineId, envelope) -> {
            if (readable.contains(engineId)) {
                perEngine.put(engineId, envelope);
            }
        });

        List<ErrorGroup> errorGroups = new ArrayList<>();
        for (ErrorGroup group : dashboard.errorGroups()) {
            Map<String, Map<String, Long>> full = group.countsByEngine() != null ? group.countsByEngine() : Map.of();
            Map<String, Map<String, Long>> scoped = new LinkedHashMap<>();
            full.forEach((engineId, byDefVersion) -> {
                if (readable.contains(engineId)) {
                    scoped.put(engineId, byDefVersion);
                }
            });
            if (scoped.isEmpty()) {
                continue; // the whole group is another engine's/tenant's failure — drop it
            }
            boolean partial = scoped.size() < full.size();
            long total = scoped.values().stream()
                    .flatMap(byDefVersion -> byDefVersion.values().stream())
                    .mapToLong(Long::longValue)
                    .sum();
            Long deadLetterCount = partial ? null : group.deadLetterCount();
            Long retryingCount = partial ? null : group.retryingCount();
            errorGroups.add(new ErrorGroup(
                    group.signatureHash(),
                    group.algoVersion(),
                    group.exceptionClass(),
                    group.normalizedMessage(),
                    group.sampleRawMessage(),
                    total,
                    deadLetterCount,
                    retryingCount,
                    scoped,
                    group.acknowledgement()));
        }

        return new TriageDashboardResponse(
                dashboard.asOf(), engines, statusCounts, statusCountsByEngine, errorGroups, perEngine);
    }
}
