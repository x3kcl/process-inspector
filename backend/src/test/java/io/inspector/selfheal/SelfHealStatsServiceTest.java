package io.inspector.selfheal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.SelfHealStats;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentOccurrenceRepository;
import io.inspector.incident.IncidentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: {@code SelfHealStatsService}'s JPA-facing glue with mocked stores — the safe read-path
 * default, per-class caching, the dwell tick's poisoned-class isolation, and the confound
 * query's engine scoping. The MATH itself (spell extraction, Wilson bounds, the dwell/hysteresis
 * machine) is proven exhaustively and independently against pure {@code SpellSample} fixtures —
 * {@code RetrySpellExtractorTest}, {@code SelfHealStatsComputerTest}, {@code WilsonIntervalTest},
 * {@code DwellStateMachineTest}. {@code IncidentOccurrence} has no public constructor ANYWHERE in
 * this codebase (JPA hydration only — see its class doc); no test anywhere builds one directly,
 * so occurrence-series behavior against a real store is {@code IncidentLedgerIT}'s rung-4
 * territory. {@code Incident} is mocked here exactly like {@code IncidentLedgerServiceTest} does.
 */
class SelfHealStatsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentOccurrenceRepository occurrences = mock(IncidentOccurrenceRepository.class);
    private final AuditEntryRepository audits = mock(AuditEntryRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SelfHealStatsService service = new SelfHealStatsService(
            incidents,
            occurrences,
            audits,
            json,
            clock,
            new InspectorProperties(null, null, null, null, null, null, null, null));

    private static Incident incident(long id, String hash, int algoVersion, String countsByEngine) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(id);
        when(row.getSignatureHash()).thenReturn(hash);
        when(row.getAlgoVersion()).thenReturn(algoVersion);
        when(row.getCountsByEngine()).thenReturn(countsByEngine);
        return row;
    }

    @Test
    void anUnknownClassAnswersTheSafeInsufficientHistoryDefault() {
        when(incidents.findBySignatureHashAndAlgoVersion("hash-x", 1)).thenReturn(Optional.empty());

        SelfHealStats stats = service.get("hash-x", 1);

        assertThat(stats.lane()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(stats.n()).isZero();
        assertThat(stats.healed()).isZero();
        assertThat(stats.wilsonLow()).isNull();
        assertThat(stats.wilsonHigh()).isNull();
        assertThat(stats.ttsP50Seconds()).isNull();
        assertThat(stats.ttsP90Seconds()).isNull();
        assertThat(stats.excludedSpells()).isZero();
        assertThat(stats.truncationTainted()).isFalse();
    }

    @Test
    void aKnownClassWithAnEmptySeriesIsAlsoInsufficientHistoryAndQueriesConfoundsOnItsEngines() {
        Incident row = incident(1L, "hash-1", 1, "{\"engine-a\":{\"def:v1\":5}}");
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(Optional.of(row));
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(eq(1L), any()))
                .thenReturn(List.of());
        when(audits.findSuccessfulRetryJobAudits(anyCollection(), any(), eq(AuditOutcome.ok)))
                .thenReturn(List.of());

        SelfHealStats stats = service.get("hash-1", 1);

        assertThat(stats.lane()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(stats.n()).isZero();
        verify(audits).findSuccessfulRetryJobAudits(eq(java.util.Set.of("engine-a")), any(), eq(AuditOutcome.ok));
    }

    @Test
    void aClassWithNoEnginesInItsDisplayBlobSkipsTheConfoundQueryEntirely() {
        Incident row = incident(2L, "hash-2", 1, "{}");
        when(incidents.findBySignatureHashAndAlgoVersion("hash-2", 1)).thenReturn(Optional.of(row));
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(eq(2L), any()))
                .thenReturn(List.of());

        service.get("hash-2", 1);

        verify(audits, never()).findSuccessfulRetryJobAudits(anyCollection(), any(), any());
    }

    @Test
    void aCorruptedDisplayBlobDegradesToNoConfoundDetectionRatherThanThrowing() {
        Incident row = incident(3L, "hash-3", 1, "not-json");
        when(incidents.findBySignatureHashAndAlgoVersion("hash-3", 1)).thenReturn(Optional.of(row));
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(eq(3L), any()))
                .thenReturn(List.of());

        assertThatCode(() -> service.get("hash-3", 1)).doesNotThrowAnyException();
        verify(audits, never()).findSuccessfulRetryJobAudits(anyCollection(), any(), any());
    }

    @Test
    void repeatedReadsWithinTheCacheTtlHitTheRepositoryOnlyOnce() {
        Incident row = incident(4L, "hash-4", 1, "{}");
        when(incidents.findBySignatureHashAndAlgoVersion("hash-4", 1)).thenReturn(Optional.of(row));
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(eq(4L), any()))
                .thenReturn(List.of());

        service.get("hash-4", 1);
        service.get("hash-4", 1);
        service.get("hash-4", 1);

        verify(incidents, times(1)).findBySignatureHashAndAlgoVersion("hash-4", 1);
    }

    @Test
    void aDwellTickNeverThrowsAndSkipsOnlyThePoisonedClass() {
        Incident bad = incident(5L, "hash-bad", 1, "{}");
        Incident good = incident(6L, "hash-good", 1, "{}");
        when(incidents.findAll()).thenReturn(List.of(bad, good));
        when(incidents.findBySignatureHashAndAlgoVersion("hash-good", 1)).thenReturn(Optional.of(good));
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(eq(5L), any()))
                .thenThrow(new RuntimeException("boom — this class's read is poisoned"));
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(eq(6L), any()))
                .thenReturn(List.of());

        assertThatCode(() -> service.tick(true, NOW)).doesNotThrowAnyException();

        // the good class was still ticked (cached) despite the bad one blowing up first-or-second.
        SelfHealStats goodStats = service.get("hash-good", 1);
        assertThat(goodStats.lane()).isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    void theEventListenerNeverThrowsEvenWhenTheLedgerRepositoryItselfIsUnavailable() {
        when(incidents.findAll()).thenThrow(new RuntimeException("db unavailable"));

        assertThatCode(() -> service.onAggregationSampled(sampledEvent())).doesNotThrowAnyException();
    }

    @Test
    void disablingTheFlagSkipsTheTickEntirelyButReadsStayLive() {
        InspectorProperties disabled = new InspectorProperties(
                null, null, null, null, null, null, new InspectorProperties.SelfHeal(false, null, null, null), null);
        SelfHealStatsService serviceWithTickOff =
                new SelfHealStatsService(incidents, occurrences, audits, json, clock, disabled);

        serviceWithTickOff.onAggregationSampled(sampledEvent());

        verify(incidents, never()).findAll();
        // reads still answer the safe default — the flag never gates GET /api/incidents.
        when(incidents.findBySignatureHashAndAlgoVersion("hash-off", 1)).thenReturn(Optional.empty());
        assertThat(serviceWithTickOff.get("hash-off", 1).lane()).isEqualTo("INSUFFICIENT_HISTORY");
    }

    private static io.inspector.snapshot.AggregationSampledEvent sampledEvent() {
        io.inspector.snapshot.AggregationSample sample =
                new io.inspector.snapshot.AggregationSample(List.of(), List.of(), NOW, java.util.Set.of(), true);
        return new io.inspector.snapshot.AggregationSampledEvent(sample, NOW);
    }
}
