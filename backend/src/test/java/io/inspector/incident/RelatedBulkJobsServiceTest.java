package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditEntryRepository;
import io.inspector.bulk.BulkJob;
import io.inspector.bulk.BulkJobItem;
import io.inspector.bulk.BulkJobItemRepository;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.dto.IncidentDetail;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 for the S5 related-bulk-jobs join: the audit envelope is the join table (job ids come
 * from {@code payload.errorClass}-matching envelope rows, newest first), the job/item stores are
 * batch-read, and every defensive branch (missing job row, unparseable id, duplicate id, empty
 * result) degrades to omission — never an exception on a read path. Also proves the R-SAFE-17
 * scope narrowing (issue #329): a job touching no readable engine is OMITTED, a job spanning both
 * a readable and unreadable engine is NARROWED (items/tallies/totalItems recomputed from the
 * visible subset), and {@code null} (enforcement off) is unaffected. The REAL end-to-end proof
 * (a live error-class submit surfacing on the incident detail) is {@code IncidentLedgerArcIT}.
 */
class RelatedBulkJobsServiceTest {

    private static final String HASH = "hash-1";
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    private final AuditEntryRepository audits = mock(AuditEntryRepository.class);
    private final BulkJobRepository jobs = mock(BulkJobRepository.class);
    private final BulkJobItemRepository items = mock(BulkJobItemRepository.class);
    private final RelatedBulkJobsService service = new RelatedBulkJobsService(audits, jobs, items);

    @Test
    void mapsJobsInAuditOrderWithBatchedTallies() {
        UUID newer = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        // built BEFORE the when(...) chains below — item() stubs a fresh mock, and nesting that
        // inside another stubbing's argument list is the classic UnfinishedStubbingException
        List<BulkJobItem> itemRows = List.of(
                item(newer, BulkJobItem.State.ok),
                item(newer, BulkJobItem.State.ok),
                item(newer, BulkJobItem.State.failed),
                item(older, BulkJobItem.State.skipped));
        when(audits.findRecentErrorClassBulkJobIds(HASH, "1", RelatedBulkJobsService.RECENT_LIMIT))
                .thenReturn(List.of(newer.toString(), older.toString()));
        // the store answers in ITS OWN order — the audit (submit-recency) order must win
        when(jobs.findAllById(List.of(newer, older))).thenReturn(List.of(job(older), job(newer)));
        when(items.findByJobIdIn(any())).thenReturn(itemRows);

        List<IncidentDetail.RelatedBulkJob> out = service.forSignature(HASH, 1, null);

        assertThat(out).extracting(IncidentDetail.RelatedBulkJob::id).containsExactly(newer, older);
        assertThat(out.get(0).tallies()).containsEntry("ok", 2L).containsEntry("failed", 1L);
        assertThat(out.get(1).tallies()).containsOnly(java.util.Map.entry("skipped", 1L));
        assertThat(out.get(0).verb()).isEqualTo("retry-job");
        assertThat(out.get(0).scopeKind()).isEqualTo("ERROR_CLASS");
        assertThat(out.get(0).scopeLabel()).isEqualTo("order v3 · error class");
    }

    @Test
    void skipsAuditRowsWhoseJobIsGoneOrUnparseable() {
        UUID present = UUID.randomUUID();
        UUID vanished = UUID.randomUUID();
        when(audits.findRecentErrorClassBulkJobIds(HASH, "1", RelatedBulkJobsService.RECENT_LIMIT))
                .thenReturn(List.of("not-a-uuid", vanished.toString(), present.toString(), present.toString()));
        when(jobs.findAllById(List.of(vanished, present))).thenReturn(List.of(job(present)));
        when(items.findByJobIdIn(any())).thenReturn(List.of());

        List<IncidentDetail.RelatedBulkJob> out = service.forSignature(HASH, 1, null);

        assertThat(out).extracting(IncidentDetail.RelatedBulkJob::id).containsExactly(present);
        assertThat(out.get(0).tallies()).isEmpty(); // no item rows found — histogram degrades empty
    }

    @Test
    void noMatchingEnvelopesMeansAnEmptyListAndNoStoreReads() {
        when(audits.findRecentErrorClassBulkJobIds(HASH, "1", RelatedBulkJobsService.RECENT_LIMIT))
                .thenReturn(List.of());

        assertThat(service.forSignature(HASH, 1, null)).isEmpty();

        verify(jobs, never()).findAllById(any());
        verify(items, never()).findByJobIdIn(any());
    }

    /* ---------------- R-SAFE-17 scope narrowing (issue #329) ---------------- */

    @Test
    void aJobTouchingNoReadableEngineIsOmittedEntirely() {
        UUID onlyEngineB = UUID.randomUUID();
        when(audits.findRecentErrorClassBulkJobIds(HASH, "1", RelatedBulkJobsService.RECENT_LIMIT))
                .thenReturn(List.of(onlyEngineB.toString()));
        when(jobs.findAllById(List.of(onlyEngineB))).thenReturn(List.of(job(onlyEngineB)));
        // the job's items are ALL on engine-b — none survive the engine-a-only readable set
        when(items.findByJobIdInAndEngineIdIn(any(), eq(Set.of("engine-a")))).thenReturn(List.of());

        List<IncidentDetail.RelatedBulkJob> out = service.forSignature(HASH, 1, Set.of("engine-a"));

        assertThat(out).isEmpty();
        verify(items, never()).findByJobIdIn(any());
    }

    @Test
    void aJobSpanningBothEnginesIsNarrowedWithRecomputedTalliesAndTotal() {
        UUID spanning = UUID.randomUUID();
        when(audits.findRecentErrorClassBulkJobIds(HASH, "1", RelatedBulkJobsService.RECENT_LIMIT))
                .thenReturn(List.of(spanning.toString()));
        BulkJob job = job(spanning); // job.getTotalItems() == 3, spanning engine-a + engine-b
        // built BEFORE the when(...) chains below — see the class's first test for why
        List<BulkJobItem> visibleItems =
                List.of(item(spanning, BulkJobItem.State.ok), item(spanning, BulkJobItem.State.ok));
        when(jobs.findAllById(List.of(spanning))).thenReturn(List.of(job));
        // 2 of the 3 items are on the readable engine-a; 1 (a "failed") is on engine-b and must
        // never surface here — the sharpest leak issue #329 closes.
        when(items.findByJobIdInAndEngineIdIn(any(), eq(Set.of("engine-a")))).thenReturn(visibleItems);

        List<IncidentDetail.RelatedBulkJob> out = service.forSignature(HASH, 1, Set.of("engine-a"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).totalItems())
                .isEqualTo(2); // recomputed from the visible subset, not job.getTotalItems()==3
        assertThat(out.get(0).tallies()).containsOnly(java.util.Map.entry("ok", 2L));
    }

    @Test
    void anUnscopedCallerIsUnaffected_legacyFullSignatureMatchStands() {
        UUID id = UUID.randomUUID();
        when(audits.findRecentErrorClassBulkJobIds(HASH, "1", RelatedBulkJobsService.RECENT_LIMIT))
                .thenReturn(List.of(id.toString()));
        // built BEFORE the when(...) chains below — see the class's first test for why
        List<BulkJobItem> allItems = List.of(
                item(id, BulkJobItem.State.ok), item(id, BulkJobItem.State.ok), item(id, BulkJobItem.State.failed));
        when(jobs.findAllById(List.of(id))).thenReturn(List.of(job(id)));
        when(items.findByJobIdIn(any())).thenReturn(allItems);

        List<IncidentDetail.RelatedBulkJob> out = service.forSignature(HASH, 1, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).totalItems()).isEqualTo(3);
        verify(items, never()).findByJobIdInAndEngineIdIn(any(), any());
    }

    private static BulkJob job(UUID id) {
        BulkJob job = new BulkJob(
                id,
                "responder",
                NOW.minusSeconds(300),
                "retry-job",
                "IT: retry the class",
                null,
                3,
                null,
                BulkJob.ScopeKind.ERROR_CLASS,
                "order v3 · error class");
        job.markRunning();
        job.finish(BulkJob.State.COMPLETED, NOW.minusSeconds(280));
        return job;
    }

    private static BulkJobItem item(UUID jobId, BulkJobItem.State state) {
        BulkJobItem item = mock(BulkJobItem.class);
        when(item.getJobId()).thenReturn(jobId);
        when(item.getState()).thenReturn(state);
        return item;
    }
}
