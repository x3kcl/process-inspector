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
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.triage.LeakViewService;
import io.inspector.triage.TriageService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * {@code GET /api/diag} assembly (issue #96, OPERATIONS.md §2) — pure aggregation, no new state of
 * its own: every signal is read from something that already exists (the resilience4j registry,
 * the triage/leak-views caches' own {@code asOf}, the bulk permit pools, {@link
 * RecentEngineErrors}) so this class can never itself drift from the live truth.
 *
 * <p>The door check ({@code @PreAuthorize} on {@link io.inspector.api.DiagController}) is
 * deliberately the coarse {@code atLeast(ADMIN)} — "is this caller an ADMIN anywhere" — the SAME
 * shape {@link io.inspector.api.AuditController#operationsLog} uses for the cross-engine
 * operations log. Per-engine sections are then filtered here, per entry, to engines the caller
 * actually holds ADMIN on ({@link RbacAuthorizer#hasRoleOn}) — mirroring {@code
 * AuditController#payloadVisible}. A per-engine-scoped ADMIN must not see another engine's breaker
 * state, permit saturation, or recent failures via this fleet-wide endpoint (ScopeGrant's own
 * invariant: a grant on one engine/tenant authorizes nothing on another).
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
    private final RbacAuthorizer rbac;
    private final Clock clock;

    public DiagService(
            CircuitBreakerRegistry breakers,
            TriageService triage,
            LeakViewService leakViews,
            BulkJobService bulk,
            RecentEngineErrors recentErrors,
            ObjectProvider<BuildProperties> buildProperties,
            RbacAuthorizer rbac,
            Clock clock) {
        this.breakers = breakers;
        this.triage = triage;
        this.leakViews = leakViews;
        this.bulk = bulk;
        this.recentErrors = recentErrors;
        this.buildProperties = buildProperties;
        this.rbac = rbac;
        this.clock = clock;
    }

    public DiagResponse diag(Authentication authentication) {
        List<BreakerStatus> breakerStatuses = breakers.getAllCircuitBreakers().stream()
                .filter(cb -> visible(authentication, engineIdOf(cb.getName())))
                .map(cb -> new BreakerStatus(cb.getName(), cb.getState().name()))
                .toList();

        List<CacheStatus> cacheStatuses = List.of(
                new CacheStatus("triage-dashboard", ageSeconds(triage.cacheAge())),
                new CacheStatus("leak-views", ageSeconds(leakViews.cacheAge())));

        List<PermitStatus> permitStatuses = bulk.permitSnapshot().stream()
                .filter(p -> visible(authentication, p.engineId()))
                .map(p -> new PermitStatus(p.engineId(), p.available(), p.total()))
                .toList();

        List<RecentError> recent = recentErrors.recent(RECENT_ERROR_LIMIT).stream()
                .filter(e -> visible(authentication, e.engineId()))
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

    private boolean visible(Authentication authentication, String engineId) {
        return rbac.hasRoleOn(authentication, Role.ADMIN, engineId);
    }

    /** Breaker/bulkhead instance names are {@code engineId} or {@code engineId:<lane-suffix>}. */
    private static String engineIdOf(String instanceName) {
        int colon = instanceName.indexOf(':');
        return colon < 0 ? instanceName : instanceName.substring(0, colon);
    }

    private static Long ageSeconds(Optional<Duration> age) {
        return age.map(Duration::toSeconds).orElse(null);
    }
}
