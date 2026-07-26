package io.inspector.incident;

import io.inspector.audit.AuditEntryRepository;
import io.inspector.bulk.BulkJob;
import io.inspector.bulk.BulkJobItem;
import io.inspector.bulk.BulkJobItemRepository;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.dto.IncidentDetail;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>Scope-filtered reads too (S2, R-SAFE-17, issue #329).</b> An error-class signature can
 * span multiple engines, so a bulk job matched here can too — this join used to read {@code
 * BulkJobRepository}/{@code BulkJobItemRepository} directly and therefore stayed UNPROJECTED by
 * the bulk surface's own R-SAFE-17 narrowing (issue #296), even though the incident detail's OWN
 * zero-intersection check only gates whether the caller can see the INCIDENT at all, not whether
 * every related job is fully in the caller's scope. Fixed by threading the SAME {@code
 * readableEngineIds} set {@link IncidentQueryService} already resolved for the incident's own
 * projection into this join, and applying the IDENTICAL "job touches no readable engine → omit;
 * partially in scope → narrow items + recompute tallies/totalItems" doctrine issue #296 built for
 * {@code BulkJobService#recent}/{@code #get} — reusing {@code BulkJobItemRepository}'s scoped
 * batch query. {@code null} = enforcement off = unrestricted (never conflated with an empty set,
 * which means "caller can read nothing").
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

    /**
     * The most recent error-class bulk jobs submitted against this signature, newest first,
     * narrowed to {@code readableEngineIds} (R-SAFE-17, issue #329) — {@code null} means
     * enforcement is off and every matching job is returned verbatim (legacy behaviour).
     */
    public List<IncidentDetail.RelatedBulkJob> forSignature(
            String signatureHash, int algoVersion, Set<String> readableEngineIds) {
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
        Map<UUID, List<BulkJobItem>> itemsByJob = itemsByJob(byId.keySet(), readableEngineIds);
        List<IncidentDetail.RelatedBulkJob> out = new ArrayList<>();
        for (UUID id : ordered) {
            BulkJob job = byId.get(id);
            if (job == null) {
                continue; // audit row outlived the job store's content — nothing to show
            }
            List<BulkJobItem> visible = itemsByJob.getOrDefault(id, List.of());
            if (readableEngineIds != null && visible.isEmpty()) {
                // job touches no readable engine — OMITTED entirely (issue #296 doctrine,
                // BulkJobService#recent), never listed with a hidden/empty item set.
                continue;
            }
            out.add(new IncidentDetail.RelatedBulkJob(
                    job.getId(),
                    job.getVerb(),
                    job.getState().name(),
                    job.getSubmittedBy(),
                    job.getSubmittedAt(),
                    job.getFinishedAt(),
                    // Recomputed from the (possibly narrowed) visible items — the exact
                    // BulkDtos.BulkJobDto.of doctrine this join is documented to mirror; a
                    // no-op under enforcement-off/full-scope since `visible` is then the
                    // job's complete item set.
                    visible.size(),
                    job.getScopeKind().name(),
                    job.getScopeLabel(),
                    tally(visible)));
        }
        return out;
    }

    /**
     * Batched per-job item lists, scoped like {@code BulkJobService#itemsFor} (issue #296/#329):
     * {@code null} readableEngineIds reads every item ({@code findByJobIdIn}); a concrete set
     * pushes the predicate into ONE query ({@code findByJobIdInAndEngineIdIn}) — never a per-job
     * read, and never an in-memory filter over an unscoped fetch.
     */
    private Map<UUID, List<BulkJobItem>> itemsByJob(Collection<UUID> jobIds, Set<String> readableEngineIds) {
        Map<UUID, List<BulkJobItem>> byJob = new LinkedHashMap<>();
        if (jobIds.isEmpty()) {
            return byJob;
        }
        List<BulkJobItem> rows;
        if (readableEngineIds == null) {
            rows = items.findByJobIdIn(jobIds);
        } else if (readableEngineIds.isEmpty()) {
            rows = List.of(); // caller can read no engine at all — nothing visible
        } else {
            rows = items.findByJobIdInAndEngineIdIn(jobIds, readableEngineIds);
        }
        for (BulkJobItem item : rows) {
            byJob.computeIfAbsent(item.getJobId(), id -> new ArrayList<>()).add(item);
        }
        return byJob;
    }

    /** Per-item state histogram ({@code BulkJobDto.of}'s tally, over one job's visible items). */
    private static Map<String, Long> tally(List<BulkJobItem> visible) {
        Map<String, Long> tallies = new LinkedHashMap<>();
        for (BulkJobItem item : visible) {
            tallies.merge(item.getState().name(), 1L, Long::sum);
        }
        return tallies;
    }
}
