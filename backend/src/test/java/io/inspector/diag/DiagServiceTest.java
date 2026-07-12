package io.inspector.diag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.bulk.BulkJobService;
import io.inspector.bulk.BulkJobService.EnginePermitSnapshot;
import io.inspector.client.RecentEngineErrors;
import io.inspector.dto.DiagResponse;
import io.inspector.triage.LeakViewService;
import io.inspector.triage.TriageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/**
 * Rung 1/2 for {@code GET /api/diag} (issue #96): pure aggregation over mocked signal sources —
 * every dependency is EITHER already tested on its own (breaker registry, cache-age accessors,
 * permit snapshot, recent-errors ring buffer) or a framework type (BuildProperties), so this test
 * only pins how DiagService assembles them into one response.
 */
class DiagServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final CircuitBreakerRegistry breakers = mock(CircuitBreakerRegistry.class);
    private final TriageService triage = mock(TriageService.class);
    private final LeakViewService leakViews = mock(LeakViewService.class);
    private final BulkJobService bulk = mock(BulkJobService.class);
    private final RecentEngineErrors recentErrors = mock(RecentEngineErrors.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);

    private final DiagService service =
            new DiagService(breakers, triage, leakViews, bulk, recentErrors, buildProperties, clock);

    @Test
    void assemblesBreakerCacheAndPermitSignalsFromTheirOwnSources() {
        CircuitBreaker cb = mock(CircuitBreaker.class);
        when(cb.getName()).thenReturn("engine-a");
        when(cb.getState()).thenReturn(CircuitBreaker.State.OPEN);
        when(breakers.getAllCircuitBreakers()).thenReturn(java.util.Set.of(cb));

        when(triage.cacheAge()).thenReturn(Optional.of(Duration.ofSeconds(5)));
        when(leakViews.cacheAge()).thenReturn(Optional.empty());

        when(bulk.permitSnapshot()).thenReturn(List.of(new EnginePermitSnapshot("engine-a", 2, 4)));

        when(recentErrors.recent(20))
                .thenReturn(List.of(
                        new RecentEngineErrors.Entry(NOW, "engine-a", "INTERACTIVE", "IOException", "boom", "req-1")));

        when(buildProperties.stream()).thenReturn(java.util.stream.Stream.empty());

        DiagResponse diag = service.diag();

        assertThat(diag.asOf()).isEqualTo(NOW.toString());
        assertThat(diag.breakers()).containsExactly(new DiagResponse.BreakerStatus("engine-a", "OPEN"));
        assertThat(diag.caches())
                .containsExactly(
                        new DiagResponse.CacheStatus("triage-dashboard", 5L),
                        new DiagResponse.CacheStatus("leak-views", null));
        assertThat(diag.bulkPermits()).containsExactly(new DiagResponse.PermitStatus("engine-a", 2, 4));
        assertThat(diag.recentErrors())
                .containsExactly(new DiagResponse.RecentError(
                        NOW.toString(), "engine-a", "INTERACTIVE", "IOException", "boom", "req-1"));
        assertThat(diag.build()).isNull();
    }

    @Test
    void buildInfoIsPresentWhenTheBeanExists() {
        when(breakers.getAllCircuitBreakers()).thenReturn(java.util.Set.of());
        when(triage.cacheAge()).thenReturn(Optional.empty());
        when(leakViews.cacheAge()).thenReturn(Optional.empty());
        when(bulk.permitSnapshot()).thenReturn(List.of());
        when(recentErrors.recent(20)).thenReturn(List.of());

        BuildProperties bp = new BuildProperties(new java.util.Properties() {
            {
                setProperty("version", "1.2.3");
                setProperty("artifact", "process-inspector-backend");
                setProperty("time", "2026-07-01T00:00:00Z");
            }
        });
        when(buildProperties.stream()).thenReturn(java.util.stream.Stream.of(bp));

        DiagResponse diag = service.diag();

        assertThat(diag.build())
                .isEqualTo(new DiagResponse.BuildInfo(
                        "1.2.3", "process-inspector-backend", bp.getTime().toString()));
    }
}
