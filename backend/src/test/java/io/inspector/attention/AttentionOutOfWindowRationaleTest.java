package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.dto.AttentionScore;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentEpisodeRepository;
import io.inspector.incident.IncidentOccurrenceRepository;
import io.inspector.incident.IncidentRepository;
import io.inspector.selfheal.SelfHealStatsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #660: a ledger class last seen before the model's 28-day window gets {@code ClassHistory.none()}
 * — the rationale must not then claim "N failing · last seen just now" for it. The SCORE stays as
 * it is (ordering is #661's concern); only the sentence changes.
 */
class AttentionOutOfWindowRationaleTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentEpisodeRepository episodes = mock(IncidentEpisodeRepository.class);
    private final IncidentOccurrenceRepository occurrences = mock(IncidentOccurrenceRepository.class);
    private final SelfHealStatsService selfHeal = mock(SelfHealStatsService.class);

    @Test
    void aClassLastSeenBeforeTheWindowSaysSoInsteadOfLastSeenJustNow() {
        // Given: the model window holds another class only — hash-old was last seen 96 d ago.
        List<Incident> ledger = List.of(incident(4L, "hash-live"));
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(ledger);

        // When
        AttentionScore scored = service().forClass("hash-old", 2, 13, null, NOW.minus(Duration.ofDays(96)));

        // Then: no live-count or recency claim, and the score is untouched.
        assertThat(scored.rationale()).isEqualTo("not seen in the last 28 d — nothing recent to rank on.");
        assertThat(scored.score()).isEqualTo(0.0);
    }

    @Test
    void aClassJustInsideTheWindowButMissingFromAStaleModelKeepsItsLiveSentence() {
        // Given: a brand-new class the 5-minute cached model has not picked up yet.
        List<Incident> ledger = List.of(incident(4L, "hash-live"));
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(ledger);

        // When: last seen 27 d 23 h ago — inside the 28-d window.
        AttentionScore scored = service()
                .forClass("hash-new", 2, 3, null, NOW.minus(Duration.ofDays(28)).plusSeconds(3600));

        // Then
        assertThat(scored.rationale()).startsWith("3 failing · ");
    }

    private AttentionScoreService service() {
        InspectorProperties props = new InspectorProperties(
                null,
                null,
                new InspectorProperties.Triage(null, null, null, null, true, null),
                null,
                null,
                null,
                null,
                null);
        return new AttentionScoreService(
                incidents, episodes, occurrences, selfHeal, Clock.fixed(NOW, ZoneOffset.UTC), props);
    }

    private static Incident incident(long id, String hash) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(id);
        when(row.getSignatureHash()).thenReturn(hash);
        when(row.getAlgoVersion()).thenReturn(2);
        when(row.getLastSeen()).thenReturn(NOW);
        return row;
    }
}
