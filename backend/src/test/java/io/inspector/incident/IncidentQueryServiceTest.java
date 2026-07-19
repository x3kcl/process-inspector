package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.Incidents;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.IncidentDetail;
import io.inspector.dto.IncidentSummary;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.security.ReadScopeGate;
import io.inspector.triage.ErrorGroupAckService;
import io.inspector.triage.ErrorSignatureNormalizer;
import io.inspector.triage.TriageScopeProjector;
import io.inspector.triage.TriageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1: the S2 read path with mocked stores — quiet derivation at the exact window boundary,
 * the generation split against the REAL normalizer constant, the R-SAFE-17 scope projection
 * (full-scope carry, partial-scope recompute+flag, zero-intersection omit/404-not-403), state
 * filter validation, and the 30-day window clamp on both routes. The live join is proven to be
 * the dashboard's own path (shared cache → projector → ack decoration). DB-real behavior
 * (ordering, windowed queries against Postgres) is rung-4 territory.
 */
class IncidentQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final long ID = 42L;
    private static final int CURRENT = ErrorSignatureNormalizer.ALGO_VERSION;

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentEpisodeRepository episodes = mock(IncidentEpisodeRepository.class);
    private final IncidentOccurrenceRepository occurrences = mock(IncidentOccurrenceRepository.class);
    private final ReadScopeGate gate = mock(ReadScopeGate.class);
    private final TriageService triage = mock(TriageService.class);
    private final TriageScopeProjector projector = mock(TriageScopeProjector.class);
    private final ErrorGroupAckService acks = mock(ErrorGroupAckService.class);
    private final Authentication auth = mock(Authentication.class);
    private final IncidentQueryService service = service(Duration.ofHours(24));

    /* ---------------- quiet derivation ---------------- */

    @Test
    void quietIsStrictlyPastTheConfiguredWindow() {
        // Exactly AT the boundary (lastSeen == now − quietWindow) is NOT yet quiet — strict <.
        when(gate.readableEngineIds(auth)).thenReturn(null);
        Incident atBoundary =
                row(1L, "at-boundary", CURRENT, IncidentState.OPEN, NOW.minus(Duration.ofHours(24)), 5, fleet());
        Incident pastBoundary = row(
                2L,
                "past-boundary",
                CURRENT,
                IncidentState.OPEN,
                NOW.minus(Duration.ofHours(24)).minusSeconds(1),
                5,
                fleet());
        when(incidents.findAllByOrderByLastSeenDesc()).thenReturn(List.of(atBoundary, pastBoundary));

        List<IncidentSummary> out = service.list(null, null, auth);

        assertThat(out).extracting(IncidentSummary::quiet).containsExactly(false, true);
    }

    /* ---------------- generation split ---------------- */

    @Test
    void currentGenerationTracksTheRealNormalizerConstant() {
        when(gate.readableEngineIds(auth)).thenReturn(null);
        Incident fresh = row(1L, "fresh", CURRENT, IncidentState.OPEN, NOW, 5, fleet());
        Incident orphaned = row(2L, "orphaned", CURRENT + 1, IncidentState.OPEN, NOW, 5, fleet());
        when(incidents.findAllByOrderByLastSeenDesc()).thenReturn(List.of(fresh, orphaned));

        List<IncidentSummary> out = service.list(null, null, auth);

        assertThat(out).extracting(IncidentSummary::currentGeneration).containsExactly(true, false);
    }

    /* ---------------- scope projection ---------------- */

    @Test
    void enforcementOffCarriesTheFleetRowVerbatim() {
        when(gate.readableEngineIds(auth)).thenReturn(null);
        Incident fleetRow = row(ID, "hash-1", CURRENT, IncidentState.OPEN, NOW, 12, fleet());
        when(incidents.findAllByOrderByLastSeenDesc()).thenReturn(List.of(fleetRow));

        IncidentSummary summary = service.list(null, null, auth).get(0);

        assertThat(summary.lastTotal()).isEqualTo(12);
        assertThat(summary.partial()).isFalse();
        assertThat(summary.countsByEngine()).containsOnlyKeys("engine-a", "engine-b");
    }

    @Test
    void fullyInScopeCarriesTheStoredFleetTotal() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a", "engine-b"));
        Incident fleetRow = row(ID, "hash-1", CURRENT, IncidentState.OPEN, NOW, 12, fleet());
        when(incidents.findAllByOrderByLastSeenDesc()).thenReturn(List.of(fleetRow));

        IncidentSummary summary = service.list(null, null, auth).get(0);

        assertThat(summary.lastTotal()).isEqualTo(12); // stored fleet value, not a recompute
        assertThat(summary.partial()).isFalse();
    }

    @Test
    void partialScopeRecomputesTheTotalFromSurvivorsAndFlagsIt() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a"));
        // fleet lastTotal 12 = engine-a 4 + engine-b 8; the caller may only see engine-a
        Incident fleetRow = row(ID, "hash-1", CURRENT, IncidentState.OPEN, NOW, 12, fleet());
        when(incidents.findAllByOrderByLastSeenDesc()).thenReturn(List.of(fleetRow));

        IncidentSummary summary = service.list(null, null, auth).get(0);

        assertThat(summary.lastTotal()).isEqualTo(4); // never the fleet 12 presented as scoped
        assertThat(summary.partial()).isTrue();
        assertThat(summary.countsByEngine()).containsOnlyKeys("engine-a");
    }

    @Test
    void zeroIntersectionIncidentsAreOmittedFromTheList() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a"));
        Incident mine = row(1L, "mine", CURRENT, IncidentState.OPEN, NOW, 4, "{\"engine-a\":{\"order:v3\":4}}");
        Incident foreign = row(2L, "foreign", CURRENT, IncidentState.OPEN, NOW, 8, "{\"engine-b\":{\"pay:v1\":8}}");
        when(incidents.findAllByOrderByLastSeenDesc()).thenReturn(List.of(mine, foreign));

        List<IncidentSummary> out = service.list(null, null, auth);

        assertThat(out).extracting(IncidentSummary::signatureHash).containsExactly("mine");
    }

    @Test
    void zeroIntersectionDetailIsTheSame404AsUnknown_NotA403() {
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a"));
        Incident foreign = row(ID, "foreign", CURRENT, IncidentState.OPEN, NOW, 8, "{\"engine-b\":{\"pay:v1\":8}}");
        when(incidents.findById(ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.detail(ID, 24, auth))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void unknownIdIsA404() {
        when(gate.readableEngineIds(auth)).thenReturn(null);
        when(incidents.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(99L, 24, auth))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /* ---------------- state filter ---------------- */

    @Test
    void stateFilterIsCaseInsensitiveAndHitsTheStateQuery() {
        when(gate.readableEngineIds(auth)).thenReturn(null);
        when(incidents.findByStateOrderByLastSeenDesc(IncidentState.REGRESSED)).thenReturn(List.of());

        assertThat(service.list("regressed", null, auth)).isEmpty();

        verify(incidents).findByStateOrderByLastSeenDesc(IncidentState.REGRESSED);
    }

    @Test
    void anUnknownStateIsA400() {
        assertThatThrownBy(() -> service.list("EXPLODED", null, auth))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /* ---------------- window clamping ---------------- */

    @Test
    void listWindowIsClampedToThirtyDaysAndPushedDownToTheStore() {
        when(gate.readableEngineIds(auth)).thenReturn(null);
        // A raw 100000h window must reach the store as the 720h clamp — never in-memory filtering.
        Incident recent = row(1L, "recent", CURRENT, IncidentState.OPEN, NOW.minus(Duration.ofHours(2)), 5, fleet());
        when(incidents.findAllByLastSeenGreaterThanEqualOrderByLastSeenDesc(NOW.minus(Duration.ofHours(720))))
                .thenReturn(List.of(recent));

        List<IncidentSummary> out = service.list(null, 100000, auth);

        assertThat(out).extracting(IncidentSummary::signatureHash).containsExactly("recent");
        verify(incidents).findAllByLastSeenGreaterThanEqualOrderByLastSeenDesc(NOW.minus(Duration.ofHours(720)));
        verify(incidents, never()).findAllByOrderByLastSeenDesc();
    }

    @Test
    void detailSeriesWindowIsClampedToThirtyDaysAndEchoed() {
        stubEmptyDetail(row(ID, "hash-1", CURRENT, IncidentState.OPEN, NOW, 4, fleet()));

        IncidentDetail detail = service.detail(ID, 100000, auth);

        assertThat(detail.seriesWindow()).isEqualTo("PT720H");
        verify(occurrences)
                .findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(
                        ID, NOW.minus(Duration.ofHours(720)));
    }

    /* ---------------- episodes ---------------- */

    @Test
    void episodesDeriveDurationOnlyOnceEnded() {
        stubEmptyDetail(row(ID, "hash-1", CURRENT, IncidentState.OPEN, NOW, 4, fleet()));
        IncidentEpisode live = episode(7L, IncidentState.REGRESSED, NOW.minusSeconds(60), null);
        IncidentEpisode ended =
                episode(6L, IncidentState.OPEN, NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(1)));
        when(episodes.findByIncidentIdOrderByStartedAtDesc(ID)).thenReturn(List.of(live, ended));

        IncidentDetail detail = service.detail(ID, 24, auth);

        assertThat(detail.episodes()).hasSize(2);
        assertThat(detail.episodes().get(0).durationSeconds()).isNull(); // live — no duration yet
        assertThat(detail.episodes().get(1).durationSeconds()).isEqualTo(7200L);
        assertThat(detail.episodes().get(1).startState()).isEqualTo("OPEN");
    }

    /* ---------------- the live join ---------------- */

    @Test
    void liveJoinIsTheDashboardsOwnScopedDecoratedPath() {
        Incident row = row(ID, "hash-1", CURRENT, IncidentState.OPEN, NOW, 4, fleet());
        stubEmptyDetail(row);
        ErrorGroup group = new ErrorGroup("hash-1", CURRENT, "Ex", "msg", "raw", 4, 4, 0, Map.of());
        TriageDashboardResponse cached = dashboard(group);
        TriageDashboardResponse projected = dashboard(group);
        TriageDashboardResponse decorated = dashboard(group);
        when(triage.dashboard(false)).thenReturn(cached);
        when(projector.project(cached, auth)).thenReturn(projected);
        when(acks.decorate(projected)).thenReturn(decorated);

        IncidentDetail detail = service.detail(ID, 24, auth);

        // the group comes out of the decorated (post-projection, post-ack) response — the exact
        // pipeline GET /api/triage serves
        assertThat(detail.live()).isSameAs(group);
    }

    @Test
    void aRetiredGenerationNeverMatchesTheLiveAggregation() {
        Incident row = row(ID, "hash-1", CURRENT + 1, IncidentState.OPEN, NOW, 4, fleet());
        stubEmptyDetail(row);
        ErrorGroup group = new ErrorGroup("hash-1", CURRENT, "Ex", "msg", "raw", 4, 4, 0, Map.of());
        TriageDashboardResponse dashboard = dashboard(group);
        when(triage.dashboard(false)).thenReturn(dashboard);
        when(projector.project(dashboard, auth)).thenReturn(dashboard);
        when(acks.decorate(dashboard)).thenReturn(dashboard);

        assertThat(service.detail(ID, 24, auth).live()).isNull(); // same hash, other generation
    }

    /* ---------------- fixtures ---------------- */

    private IncidentQueryService service(Duration quietWindow) {
        return new IncidentQueryService(
                incidents,
                episodes,
                occurrences,
                gate,
                triage,
                projector,
                acks,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new InspectorProperties(
                        null, null, null, null, null, new Incidents(true, quietWindow, null, null), List.of()));
    }

    /** Mocked persisted row (the entity is setter-less by design — the S1 test convention). */
    private static Incident row(
            long id,
            String hash,
            int algoVersion,
            IncidentState state,
            Instant lastSeen,
            long lastTotal,
            String countsJson) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(id);
        when(row.getSignatureHash()).thenReturn(hash);
        when(row.getAlgoVersion()).thenReturn(algoVersion);
        when(row.getExceptionClass()).thenReturn("java.net.SocketTimeoutException");
        when(row.getNormalizedMessage()).thenReturn("timeout after # ms");
        when(row.getSampleRawMessage()).thenReturn("timeout after 5000 ms");
        when(row.getState()).thenReturn(state);
        when(row.getFirstSeen()).thenReturn(lastSeen.minus(Duration.ofDays(2)));
        when(row.getLastSeen()).thenReturn(lastSeen);
        when(row.getLastTotal()).thenReturn(lastTotal);
        when(row.isLastTruncated()).thenReturn(false);
        when(row.getCountsByEngine()).thenReturn(countsJson);
        when(row.getRegressionCount()).thenReturn(1);
        when(row.getLastRegressedAt()).thenReturn(null);
        return row;
    }

    /** fleet blob: engine-a → 4, engine-b → 8 (fleet lastTotal 12). */
    private static String fleet() {
        return "{\"engine-a\":{\"order:v3\":4},\"engine-b\":{\"pay:v1\":8}}";
    }

    private static IncidentEpisode episode(long id, IncidentState startState, Instant startedAt, Instant endedAt) {
        IncidentEpisode episode = mock(IncidentEpisode.class);
        when(episode.getId()).thenReturn(id);
        when(episode.getStartState()).thenReturn(startState);
        when(episode.getStartedAt()).thenReturn(startedAt);
        when(episode.getEndedAt()).thenReturn(endedAt);
        when(episode.getPeakTotal()).thenReturn(9L);
        return episode;
    }

    private static TriageDashboardResponse dashboard(ErrorGroup... groups) {
        return new TriageDashboardResponse(NOW.toString(), List.of(), Map.of(), Map.of(), List.of(groups), Map.of());
    }

    /** Detail plumbing with an unrestricted scope, empty history/series and an empty live join. */
    private void stubEmptyDetail(Incident row) {
        when(gate.readableEngineIds(auth)).thenReturn(null);
        when(incidents.findById(row.getId())).thenReturn(Optional.of(row));
        when(episodes.findByIncidentIdOrderByStartedAtDesc(anyLong())).thenReturn(List.of());
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(
                        anyLong(), any(Instant.class)))
                .thenReturn(List.of());
        TriageDashboardResponse empty = dashboard();
        when(triage.dashboard(false)).thenReturn(empty);
        when(projector.project(any(), eq(auth))).thenReturn(empty);
        when(acks.decorate(any())).thenReturn(empty);
    }
}
