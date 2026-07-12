package io.inspector.diag;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.bulk.BulkJobService;
import io.inspector.client.RecentEngineErrors;
import io.inspector.dto.DiagResponse;
import io.inspector.dto.DiagResponse.BreakerStatus;
import io.inspector.dto.DiagResponse.BuildInfo;
import io.inspector.dto.DiagResponse.CacheStatus;
import io.inspector.dto.DiagResponse.PermitStatus;
import io.inspector.dto.DiagResponse.RecentError;
import io.inspector.triage.LeakViewService;
import io.inspector.triage.TriageService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * {@code GET /api/diag} assembly (issue #96, OPERATIONS.md §2) — pure aggregation, no new state of
 * its own: every signal is read from something that already exists (the resilience4j registry,
 * the triage/leak-views caches' own {@code asOf}, the bulk permit pools, {@link
 * RecentEngineErrors}) so this class can never itself drift from the live truth.
 */
@Service
public class DiagService {

    private static final int RECENT_ERROR_LIMIT = 20;

    private final CircuitBreakerRegistry breakers;
    private final TriageService triage;
    private final LeakViewService leakViews;
    private final BulkJobService bulk;
    private final RecentEngineErrors recentErrors;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final Clock clock;

    public DiagService(
            CircuitBreakerRegistry breakers,
            TriageService triage,
            LeakViewService leakViews,
            BulkJobService bulk,
            RecentEngineErrors recentErrors,
            ObjectProvider<BuildProperties> buildProperties,
            Clock clock) {
        this.breakers = breakers;
        this.triage = triage;
        this.leakViews = leakViews;
        this.bulk = bulk;
        this.recentErrors = recentErrors;
        this.buildProperties = buildProperties;
        this.clock = clock;
    }

    public DiagResponse diag() {
        List<BreakerStatus> breakerStatuses = breakers.getAllCircuitBreakers().stream()
                .map(cb -> new BreakerStatus(cb.getName(), cb.getState().name()))
                .toList();

        List<CacheStatus> cacheStatuses = List.of(
                new CacheStatus("triage-dashboard", ageSeconds(triage.cacheAge())),
                new CacheStatus("leak-views", ageSeconds(leakViews.cacheAge())));

        List<PermitStatus> permitStatuses = bulk.permitSnapshot().stream()
                .map(p -> new PermitStatus(p.engineId(), p.available(), p.total()))
                .toList();

        List<RecentError> recent = recentErrors.recent(RECENT_ERROR_LIMIT).stream()
                .map(e -> new RecentError(
                        e.at().toString(), e.engineId(), e.leg(), e.errorClass(), e.message(), e.correlationId()))
                .toList();

        BuildInfo build = buildProperties.stream()
                .findFirst()
                .map(bp -> new BuildInfo(
                        bp.getVersion(), bp.getArtifact(), bp.getTime().toString()))
                .orElse(null);

        return new DiagResponse(
                clock.instant().toString(), breakerStatuses, cacheStatuses, permitStatuses, recent, build);
    }

    private static Long ageSeconds(Optional<Duration> age) {
        return age.map(Duration::toSeconds).orElse(null);
    }
}
