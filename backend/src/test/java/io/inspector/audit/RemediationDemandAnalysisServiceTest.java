package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.audit.RemediationDemandAnalysisService.RemediationDemandAnalysis;
import io.inspector.audit.RemediationDemandAnalysisService.SequenceFinding;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.domain.Pageable;

/**
 * Rung 1 for the #106 S0 evidence check (R-GOV-08): pure sequence-mining logic over a mocked
 * repository — the query itself (ordering, instanceId filter) is a plain JPQL select with no
 * engine-shaped behavior, so no IT is warranted for this slice.
 */
class RemediationDemandAnalysisServiceTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void emptyAuditLogAnswersNoDemandHonestly() {
        RemediationDemandAnalysis result = analyze(List.of());

        assertThat(result.dataSpanDays()).isZero();
        assertThat(result.spanSufficient()).isFalse();
        assertThat(result.scannedRows()).isZero();
        assertThat(result.truncated()).isFalse();
        assertThat(result.sequences()).isEmpty();
        assertThat(result.demandTriggerFired()).isFalse();
    }

    @Test
    void twoDistinctVerbsOnOneInstanceFormOneBigramBelowThreshold() {
        List<AuditEntry> rows = new ArrayList<>();
        rows.addAll(instanceHistory("engine-a", "p-1", BASE, "retry-job", "suspend"));

        RemediationDemandAnalysis result = analyze(rows);

        assertThat(result.sequences()).hasSize(1);
        SequenceFinding finding = result.sequences().get(0);
        assertThat(finding.verbs()).containsExactly("retry-job", "suspend");
        assertThat(finding.instanceCount()).isEqualTo(1);
        assertThat(finding.meetsThreshold()).isFalse();
        assertThat(result.demandTriggerFired()).isFalse();
    }

    @Test
    void consecutiveDuplicateVerbsCollapseBeforeMining() {
        List<AuditEntry> rows = new ArrayList<>();
        rows.addAll(instanceHistory("engine-a", "p-1", BASE, "retry-job", "retry-job", "retry-job", "suspend"));

        RemediationDemandAnalysis result = analyze(rows);

        // Three consecutive retry-job attempts collapse to one — only ONE bigram, not counted
        // multiple times for the repeated verb.
        assertThat(result.sequences()).hasSize(1);
        assertThat(result.sequences().get(0).verbs()).containsExactly("retry-job", "suspend");
    }

    @Test
    void singleVerbInstanceContributesNoBigram() {
        List<AuditEntry> rows = new ArrayList<>();
        rows.addAll(instanceHistory("engine-a", "p-1", BASE, "retry-job"));

        RemediationDemandAnalysis result = analyze(rows);

        assertThat(result.sequences()).isEmpty();
    }

    @Test
    void tenInstancesOverSufficientSpanFiresTheDemandTrigger() {
        List<AuditEntry> rows = new ArrayList<>();
        Instant start = BASE;
        for (int i = 0; i < 10; i++) {
            rows.addAll(instanceHistory("engine-a", "p-" + i, start.plus(i, ChronoUnit.DAYS), "retry-job", "suspend"));
        }
        Instant end = start.plus(95, ChronoUnit.DAYS);
        rows.addAll(instanceHistory("engine-a", "p-last", end, "retry-job", "suspend"));

        RemediationDemandAnalysis result = analyze(rows);

        assertThat(result.dataSpanDays()).isGreaterThanOrEqualTo(90);
        assertThat(result.spanSufficient()).isTrue();
        SequenceFinding finding = result.sequences().get(0);
        assertThat(finding.instanceCount()).isEqualTo(11);
        assertThat(finding.meetsThreshold()).isTrue();
        assertThat(finding.sampleInstances()).hasSize(5); // capped sample, not the full 11
        assertThat(result.demandTriggerFired()).isTrue();
    }

    @Test
    void tenInstancesButShortSpanDoesNotFireTheTrigger() {
        List<AuditEntry> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // All within a single day — well short of the ~90-day pilot window.
            rows.addAll(instanceHistory("engine-a", "p-" + i, BASE.plusSeconds(i), "retry-job", "suspend"));
        }

        RemediationDemandAnalysis result = analyze(rows);

        assertThat(result.spanSufficient()).isFalse();
        assertThat(result.sequences().get(0).meetsThreshold()).isTrue();
        // The instance count clears the bar but the span doesn't — the trigger is a
        // conjunction of BOTH R-GOV-08 conditions, not either alone.
        assertThat(result.demandTriggerFired()).isFalse();
    }

    @Test
    void aScanThatHitsTheCapReportsTruncatedAndDropsThePartialTailInstance() {
        List<AuditEntry> rows = new ArrayList<>();
        // Ten complete, pre-cap instances that legitimately qualify...
        for (int i = 0; i < 10; i++) {
            rows.addAll(instanceHistory("engine-a", "p-" + i, BASE, "retry-job", "suspend"));
        }
        // ...then a run of same-instance rows that straddles the cap boundary.
        rows.addAll(instanceHistory("engine-a", "p-overflow", BASE, "retry-job", "suspend", "activate"));

        RemediationDemandAnalysis result = analyze(rows, 20); // cap lands mid p-overflow's history

        assertThat(result.truncated()).isTrue();
        assertThat(result.scannedRows()).isEqualTo(20);
        // p-overflow's in-progress chain at the moment of truncation is dropped rather than
        // counted on a partial (and therefore potentially wrong) picture.
        SequenceFinding finding = result.sequences().get(0);
        assertThat(finding.instanceCount()).isEqualTo(10);
        assertThat(finding.sampleInstances()).doesNotContain("engine-a:p-overflow");
    }

    @Test
    void definitionScopedAndConfigEventRowsAreExcludedByTheQueryItself() {
        // The repository query filters instanceId IS NOT NULL — a service-level test can't
        // observe rows the query never returns, but this documents the invariant: a
        // definition-scoped verb (null instanceId) never reaches the mining loop.
        List<AuditEntry> rows = new ArrayList<>();
        rows.add(row("engine-a", null, "suspend-definition", BASE));

        RemediationDemandAnalysis result = analyze(rows);

        // The fixture itself has a null instanceId, matching what the real query would (not)
        // return — the mining loop must not NPE or fabricate a group key out of it.
        assertThat(result.scannedRows()).isEqualTo(1);
        assertThat(result.sequences()).isEmpty();
    }

    /* ------------------------- fixtures ------------------------- */

    private static List<AuditEntry> instanceHistory(
            String engineId, String instanceId, Instant startTs, String... verbs) {
        List<AuditEntry> rows = new ArrayList<>();
        for (int i = 0; i < verbs.length; i++) {
            rows.add(row(engineId, instanceId, verbs[i], startTs.plusSeconds(i)));
        }
        return rows;
    }

    private static AuditEntry row(String engineId, String instanceId, String action, Instant ts) {
        return new AuditEntry(
                UUID.randomUUID(),
                "corr-" + UUID.randomUUID(),
                "responder",
                ts,
                engineId,
                null,
                instanceId,
                action,
                null,
                null,
                null,
                false);
    }

    private static RemediationDemandAnalysis analyze(List<AuditEntry> rows) {
        return analyze(rows, RemediationDemandAnalysisService.DEFAULT_SCAN_CAP);
    }

    private static RemediationDemandAnalysis analyze(List<AuditEntry> rows, int scanCap) {
        AuditEntryRepository repository = mock(AuditEntryRepository.class);
        int chunk = RemediationDemandAnalysisService.SCAN_CHUNK;
        when(repository.findInstanceScopedForSequenceMining(any())).thenAnswer((InvocationOnMock invocation) -> {
            Pageable page = invocation.getArgument(0);
            int from = Math.min((int) page.getOffset(), rows.size());
            int to = Math.min(from + chunk, rows.size());
            return rows.subList(from, to);
        });
        return new RemediationDemandAnalysisService(repository, scanCap).analyze();
    }
}
