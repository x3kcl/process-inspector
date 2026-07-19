package io.inspector.incident;

import io.inspector.audit.AuditEntryRepository;
import io.inspector.bulk.BulkJob;
import io.inspector.bulk.BulkJobItem;
import io.inspector.bulk.BulkJobItemRepository;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.dto.IncidentDetail;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The incident detail's "recent bulk retries" join (R-BAU-10 S5, INCIDENT-LEDGER.md §6):
 * READ-ONLY, no new mutation path — remediation outcomes become visible in incident context.
 *
 * <p><b>Join mechanics.</b> An error-class bulk job's persisted row carries only the V4 scope
 * descriptor ({@code ERROR_CLASS} + the human label "defKey vN · error class") — the signature
 * itself was recorded in the submit's ENVELOPE audit row ({@code payload.errorClass.*} +
 * {@code payload.bulkJobId}, {@link io.inspector.bulk.BulkErrorClassService}). So the audit
 * golden master is the join table: recent envelope rows matching {@code (signatureHash,
 * algoVersion)} yield the job ids, then ONE {@code bulk_job} batch read + ONE
 * {@code bulk_job_item} batch read build the list-item shapes (never per-job item queries).
 * A job id whose row is gone (or unparseable) is skipped quietly — the audit trail may
 * legitimately outlive or predate the job store's content.
 *
 * <p><b>Read rules mirrored, not invented.</b> The bulk-jobs surface ({@code GET /api/bulk},
 * {@code GET /api/bulk/{id}}) is VIEWER-floor with NO engine-scope projection — any caller who
 * can reach an incident's detail (VIEWER + R-SAFE-17 incident scope, enforced upstream in
 * {@link IncidentQueryService}) could already read every one of these jobs verbatim through
 * that door. Mirroring those rules therefore means: no additional per-job filtering here.
 *
 * <p>Deliberately NOT gated by {@code inspector.incidents.enabled} — same reasoning as the
 * query service it feeds: reads stay live when ingestion is off.
 */
@Service
public class RelatedBulkJobsService {

    /** "Recent" bound (the ops drawer's default list reads 20; an incident context wants fewer). */
    static final int RECENT_LIMIT = 10;

    private final AuditEntryRepository audits;
    private final BulkJobRepository jobs;
    private final BulkJobItemRepository items;

    public RelatedBulkJobsService(AuditEntryRepository audits, BulkJobRepository jobs, BulkJobItemRepository items) {
        this.audits = audits;
        this.jobs = jobs;
        this.items = items;
    }

    /** The most recent error-class bulk jobs submitted against this signature, newest first. */
    public List<IncidentDetail.RelatedBulkJob> forSignature(String signatureHash, int algoVersion) {
        List<String> rawIds =
                audits.findRecentErrorClassBulkJobIds(signatureHash, String.valueOf(algoVersion), RECENT_LIMIT);
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        // Audit order (newest submit first) is the response order — parse defensively.
        List<UUID> ordered = new ArrayList<>();
        for (String raw : rawIds) {
            try {
                UUID id = UUID.fromString(raw);
                if (!ordered.contains(id)) { // the bounded list makes contains() cheap
                    ordered.add(id);
                }
            } catch (IllegalArgumentException e) {
                // an envelope without a well-formed bulkJobId cannot be joined — skip quietly
            }
        }
        Map<UUID, BulkJob> byId = new LinkedHashMap<>();
        for (BulkJob job : jobs.findAllById(ordered)) {
            byId.put(job.getId(), job);
        }
        Map<UUID, Map<String, Long>> tallies = talliesByJob(byId.keySet());
        List<IncidentDetail.RelatedBulkJob> out = new ArrayList<>();
        for (UUID id : ordered) {
            BulkJob job = byId.get(id);
            if (job == null) {
                continue; // audit row outlived the job store's content — nothing to show
            }
            out.add(new IncidentDetail.RelatedBulkJob(
                    job.getId(),
                    job.getVerb(),
                    job.getState().name(),
                    job.getSubmittedBy(),
                    job.getSubmittedAt(),
                    job.getFinishedAt(),
                    job.getTotalItems(),
                    job.getScopeKind().name(),
                    job.getScopeLabel(),
                    tallies.getOrDefault(id, Map.of())));
        }
        return out;
    }

    /** Per-job per-item state histogram ({@code BulkJobDto.of}'s tally, batched across jobs). */
    private Map<UUID, Map<String, Long>> talliesByJob(java.util.Collection<UUID> jobIds) {
        Map<UUID, Map<String, Long>> tallies = new LinkedHashMap<>();
        if (jobIds.isEmpty()) {
            return tallies;
        }
        for (BulkJobItem item : items.findByJobIdIn(jobIds)) {
            tallies.computeIfAbsent(item.getJobId(), id -> new LinkedHashMap<>())
                    .merge(item.getState().name(), 1L, Long::sum);
        }
        return tallies;
    }
}
