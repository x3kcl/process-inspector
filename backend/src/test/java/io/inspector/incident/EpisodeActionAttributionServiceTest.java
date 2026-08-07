package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AttributedActionPoint;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.dto.IncidentDetail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/**
 * Rung 1: the issue #358 item 2 attribution join with a mocked {@code AuditEntryRepository} —
 * verb/outcome tallying, the ended-vs-live episode window boundary, the cap→truncated honesty
 * rail, and the degrade-safe empty-engine-set default. The audit-side query itself ({@code
 * AuditEntryRepository#findAttributableActionPoints}, hand-authored JPQL) is proven against a
 * REAL Postgres by {@code AuditEntryRepositoryAttributionIT} (rung 4) — this class only proves
 * the service calls it right and folds the result honestly.
 */
class EpisodeActionAttributionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    private final AuditEntryRepository audits = mock(AuditEntryRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EpisodeActionAttributionService service = new EpisodeActionAttributionService(audits, json, clock);

    @Test
    void tallyByVerbAndOutcomeForAnEndedEpisode() {
        Incident incident = incident("{\"engine-a\":{\"order:v3\":4}}");
        Instant startedAt = NOW.minus(Duration.ofHours(3));
        Instant endedAt = NOW.minus(Duration.ofHours(1));
        // built BEFORE the when(...) chain below — reading a mock's stubbed getters INSIDE a
        // different mock's stubbing call is the classic UnfinishedStubbingException/matcher-stack
        // trap (RelatedBulkJobsServiceTest's precedent).
        IncidentEpisode ended = episode(6L, startedAt, endedAt);
        when(audits.findAttributableActionPoints(eq(Set.of("engine-a")), eq(startedAt), eq(endedAt), any()))
                .thenReturn(List.of(
                        new AttributedActionPoint("retry-job", AuditOutcome.ok),
                        new AttributedActionPoint("retry-job", AuditOutcome.ok),
                        new AttributedActionPoint("retry-job", AuditOutcome.failed),
                        new AttributedActionPoint("edit-variable", AuditOutcome.ok)));

        Map<Long, IncidentDetail.EpisodeActionAttribution> out = service.forEpisodes(incident, List.of(ended));

        IncidentDetail.EpisodeActionAttribution attribution = out.get(6L);
        assertThat(attribution.count()).isEqualTo(4);
        assertThat(attribution.byVerb()).containsEntry("retry-job", 3L).containsEntry("edit-variable", 1L);
        assertThat(attribution.byOutcome()).containsEntry("ok", 3L).containsEntry("failed", 1L);
        assertThat(attribution.truncated()).isFalse();
    }

    @Test
    void aLiveEpisodeWindowRunsFromStartToNow() {
        Incident incident = incident("{\"engine-a\":{\"order:v3\":4}}");
        Instant startedAt = NOW.minusSeconds(60);
        IncidentEpisode live = episode(7L, startedAt, null);
        when(audits.findAttributableActionPoints(eq(Set.of("engine-a")), eq(startedAt), eq(NOW), any()))
                .thenReturn(List.of());

        Map<Long, IncidentDetail.EpisodeActionAttribution> out = service.forEpisodes(incident, List.of(live));

        assertThat(out.get(7L).count()).isZero();
        assertThat(out.get(7L).byVerb()).isEmpty();
        assertThat(out.get(7L).byOutcome()).isEmpty();
    }

    @Test
    void aCappedScanIsFlaggedTruncated_neverPresentedAsComplete() {
        Incident incident = incident("{\"engine-a\":{\"order:v3\":4}}");
        IncidentEpisode ended = episode(6L, NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(1)));
        List<AttributedActionPoint> capped = java.util.stream.IntStream.range(
                        0, EpisodeActionAttributionService.EPISODE_ACTION_CAP)
                .mapToObj(i -> new AttributedActionPoint("retry-job", AuditOutcome.ok))
                .toList();
        when(audits.findAttributableActionPoints(any(), any(), any(), any())).thenReturn(capped);

        Map<Long, IncidentDetail.EpisodeActionAttribution> out = service.forEpisodes(incident, List.of(ended));

        assertThat(out.get(6L).count()).isEqualTo(EpisodeActionAttributionService.EPISODE_ACTION_CAP);
        assertThat(out.get(6L).truncated()).isTrue();
    }

    @Test
    void aCorruptedCountsByEngineDegradesToTheEmptyAttribution_neverAnException() {
        Incident incident = incident("not json");
        IncidentEpisode ended = episode(6L, NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(1)));

        Map<Long, IncidentDetail.EpisodeActionAttribution> out = service.forEpisodes(incident, List.of(ended));

        assertThat(out.get(6L).count()).isZero();
        assertThat(out.get(6L).truncated()).isFalse();
        verify(audits, never()).findAttributableActionPoints(anyCollection(), any(), any(), any());
    }

    @Test
    void anEmptyEngineSetDegradesToTheEmptyAttributionForEveryEpisode() {
        Incident incident = incident("{}");
        IncidentEpisode a = episode(1L, NOW.minusSeconds(600), NOW.minusSeconds(300));
        IncidentEpisode b = episode(2L, NOW.minusSeconds(200), null);

        Map<Long, IncidentDetail.EpisodeActionAttribution> out = service.forEpisodes(incident, List.of(a, b));

        assertThat(out).hasSize(2);
        assertThat(out.get(1L).count()).isZero();
        assertThat(out.get(2L).count()).isZero();
        verify(audits, never()).findAttributableActionPoints(anyCollection(), any(), any(), any());
    }

    @Test
    void thePageRequestIsBoundedByTheEpisodeActionCap() {
        Incident incident = incident("{\"engine-a\":{\"order:v3\":4}}");
        Instant startedAt = NOW.minus(Duration.ofHours(3));
        Instant endedAt = NOW.minus(Duration.ofHours(1));
        IncidentEpisode ended = episode(6L, startedAt, endedAt);
        when(audits.findAttributableActionPoints(any(), any(), any(), any())).thenReturn(List.of());

        service.forEpisodes(incident, List.of(ended));

        verify(audits)
                .findAttributableActionPoints(
                        eq(Set.of("engine-a")),
                        eq(startedAt),
                        eq(endedAt),
                        eq(PageRequest.of(0, EpisodeActionAttributionService.EPISODE_ACTION_CAP)));
    }

    /* ---------------- fixtures ---------------- */

    private static Incident incident(String countsByEngine) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(42L);
        when(row.getCountsByEngine()).thenReturn(countsByEngine);
        return row;
    }

    private static IncidentEpisode episode(long id, Instant startedAt, Instant endedAt) {
        IncidentEpisode episode = mock(IncidentEpisode.class);
        when(episode.getId()).thenReturn(id);
        when(episode.getStartedAt()).thenReturn(startedAt);
        when(episode.getEndedAt()).thenReturn(endedAt);
        return episode;
    }
}
