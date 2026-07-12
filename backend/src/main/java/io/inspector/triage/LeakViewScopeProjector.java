package io.inspector.triage;

import io.inspector.dto.LeakViewsResponse;
import io.inspector.dto.LeakViewsResponse.LeakDefinitionCount;
import io.inspector.dto.LeakViewsResponse.LeakDefinitionCount.EngineLeakCount;
import io.inspector.security.ReadScopeGate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * S2 read scoping for leak-views (issue #126, R-SAFE-17) — the same post-cache render-time
 * projection doctrine as {@link TriageScopeProjector}: {@link LeakViewService} is served from a
 * SHARED 20s single-flight Caffeine cache, so scoping MUST be a per-request projection applied
 * AFTER the cache read, never a scope-aware cache key (would shatter single-flight) and never
 * inside the aggregation (the aggregation has no per-caller auth to project against).
 *
 * <p>Unlike {@code ErrorGroup}'s dead-letter/retrying split, every leak-view window count DOES
 * decompose per engine ({@link LeakDefinitionCount#countsByEngine}), so a scoped slice is
 * honestly recomputed from survivors — never nulled. A definition touching no readable engine is
 * dropped entirely; one only partially in scope keeps its recomputed totals and sets
 * {@code partial=true} so the UI can badge "may not cover every engine this definition runs on."
 * {@code unavailableEngines} is narrowed to readable engines (no engine-topology leak);
 * {@code lowerBound} is carried over unchanged — it is a fleet-wide honesty floor ("this data may
 * be incomplete") independent of which engine a truncation/outage is attributable to.
 */
@Component
public class LeakViewScopeProjector {

    private final ReadScopeGate gate;

    public LeakViewScopeProjector(ReadScopeGate gate) {
        this.gate = gate;
    }

    public LeakViewsResponse project(LeakViewsResponse response, Authentication auth) {
        Set<String> readable = gate.readableEngineIds(auth);
        if (readable == null) {
            return response; // enforcement off — the legacy fleet-wide response
        }

        List<LeakDefinitionCount> scoped = new ArrayList<>();
        for (LeakDefinitionCount def : response.definitions()) {
            Map<String, EngineLeakCount> full = def.countsByEngine() != null ? def.countsByEngine() : Map.of();
            Map<String, EngineLeakCount> narrowed = new LinkedHashMap<>();
            full.forEach((engineId, counts) -> {
                if (readable.contains(engineId)) {
                    narrowed.put(engineId, counts);
                }
            });
            if (narrowed.isEmpty()) {
                continue; // this key belongs entirely to engines outside scope
            }
            boolean partial = narrowed.size() < full.size();
            long active30 = narrowed.values().stream()
                    .mapToLong(EngineLeakCount::activeOver30d)
                    .sum();
            long active90 = narrowed.values().stream()
                    .mapToLong(EngineLeakCount::activeOver90d)
                    .sum();
            long suspended7 = narrowed.values().stream()
                    .mapToLong(EngineLeakCount::suspendedStartedOver7d)
                    .sum();
            scoped.add(new LeakDefinitionCount(def.definitionKey(), active30, active90, suspended7, narrowed, partial));
        }

        List<String> unavailable = response.unavailableEngines().stream()
                .filter(readable::contains)
                .toList();

        return new LeakViewsResponse(response.asOf(), response.windows(), scoped, response.lowerBound(), unavailable);
    }
}
