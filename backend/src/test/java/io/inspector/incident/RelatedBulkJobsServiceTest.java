package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 for the S5 related-bulk-jobs join: the audit envelope is the join table (job ids come
 * from {@code payload.errorClass}-matching envelope rows, newest first), the job/item stores are
 * batch-read, and every defensive branch (missing job row, unparseable id, duplicate id, empty
 * result) degrades to omission — never an exception on a read path. The REAL end-to-end proof
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

        List<IncidentDetail.RelatedBulkJob> out = service.forSignature(HASH, 1);

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

        List<IncidentDetail.RelatedBulkJob> out = service.forSignature(HASH, 1);

        assertThat(out).extracting(IncidentDetail.RelatedBulkJob::id).containsExactly(present);
        assertThat(out.get(0).tallies()).isEmpty(); // no item rows found — histogram degrades empty
    }

    @Test
    void noMatchingEnvelopesMeansAnEmptyListAndNoStoreReads() {
        when(audits.findRecentErrorClassBulkJobIds(HASH, "1", RelatedBulkJobsService.RECENT_LIMIT))
                .thenReturn(List.of());

        assertThat(service.forSignature(HASH, 1)).isEmpty();

        verify(jobs, never()).findAllById(any());
        verify(items, never()).findByJobIdIn(any());
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
