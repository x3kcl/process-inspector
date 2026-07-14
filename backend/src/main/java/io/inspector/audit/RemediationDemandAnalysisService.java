package io.inspector.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Issue #106 S0: the "honest gating" measurement slice for the v2 remediation-playbooks
 * headline. R-GOV-08's build trigger reads: "audit analysis over >=3 pilot months shows
 * repeated identical >=2-verb sequences on >=10 instances of one signature." This service
 * runs exactly that analysis on demand — it does NOT build the playbook feature itself
 * (record-from-exemplar / replay-as-bulk-job, steps 2/3), only the evidence step 1 gates on.
 *
 * <p><b>"Signature" approximation, stated plainly:</b> {@code audit_entry} records the
 * corrective action taken, not the instance's error signature at the time it was taken —
 * adding that would be a schema change of its own, out of scope for a measurement slice.
 * This analysis uses the ordered, adjacent, distinct-verb PAIR ("bigram") applied to one
 * instance as the mining unit instead of a true error-signature grouping — the minimal unit
 * that satisfies "a >=2-verb sequence" literally. Two instances hitting "retry-job" then
 * "suspend" count as the same finding regardless of whether they share an error class. This
 * is a deliberately conservative proxy, documented so a future maintainer doesn't mistake it
 * for signature-precise measurement (a stronger version would need audit_entry to capture the
 * signature at insert time — a plausible follow-up, not bundled here).
 */
@Service
public class RemediationDemandAnalysisService {

    static final int SCAN_CHUNK = 500;

    /** Hard ceiling so a busy multi-year audit trail can never make this an unbounded scan. */
    static final int DEFAULT_SCAN_CAP = 50_000;

    static final int MIN_SEQUENCE_LENGTH = 2;
    static final int MIN_INSTANCE_COUNT = 10;

    /** R-GOV-08's "3 pilot months" — the calendar-agnostic ~90-day approximation. */
    static final long MIN_SPAN_DAYS = 90;

    private final AuditEntryRepository repository;
    private final int scanCap;

    @Autowired
    public RemediationDemandAnalysisService(AuditEntryRepository repository) {
        this(repository, DEFAULT_SCAN_CAP);
    }

    /** Package-visible: lets rung-1 tests exercise the truncation path without 50k fixture rows. */
    RemediationDemandAnalysisService(AuditEntryRepository repository, int scanCap) {
        this.repository = repository;
        this.scanCap = scanCap;
    }

    public RemediationDemandAnalysis analyze() {
        Map<List<String>, Set<String>> bigramInstances = new LinkedHashMap<>();
        Instant earliest = null;
        Instant latest = null;
        long scanned = 0;
        boolean truncated = false;

        String currentKey = null;
        List<String> currentVerbs = new ArrayList<>();

        scan:
        for (int page = 0; ; page++) {
            List<AuditEntry> rows = repository.findInstanceScopedForSequenceMining(PageRequest.of(page, SCAN_CHUNK));
            for (AuditEntry row : rows) {
                String key = row.getEngineId() + ":" + row.getInstanceId();
                // The cap is checked at an INSTANCE BOUNDARY, not a raw row count: one
                // instance's history is always contiguous in this ordering, so stopping only
                // when a NEW key starts means the instance we just finished is always
                // complete, never a partial chain reported as if it were the whole picture.
                // The row count actually scanned may slightly exceed scanCap to reach that
                // boundary — an honest, bounded overshoot rather than an arbitrary cutoff.
                if (scanned >= scanCap && !key.equals(currentKey)) {
                    truncated = true;
                    break scan;
                }
                scanned++;
                Instant ts = row.getTs();
                if (ts != null) {
                    earliest = earliest == null || ts.isBefore(earliest) ? ts : earliest;
                    latest = latest == null || ts.isAfter(latest) ? ts : latest;
                }
                if (!key.equals(currentKey)) {
                    recordBigrams(bigramInstances, currentKey, currentVerbs);
                    currentKey = key;
                    currentVerbs = new ArrayList<>();
                }
                String verb = row.getAction();
                if (currentVerbs.isEmpty()
                        || !currentVerbs.get(currentVerbs.size() - 1).equals(verb)) {
                    currentVerbs.add(verb);
                }
            }
            // A short page ends the scan — the query is a single ordered JPQL fetch, so a
            // non-final page is always exactly SCAN_CHUNK rows (mirrors AuditController's
            // operationsLogCsv chunking, external-review-verified there).
            if (rows.size() < SCAN_CHUNK) {
                break;
            }
        }
        // Whatever's left in currentKey/currentVerbs is always complete at this point: either
        // the natural end of data, or the last instance the cap check above let finish before
        // stopping (never a key we broke out of mid-way).
        recordBigrams(bigramInstances, currentKey, currentVerbs);

        List<SequenceFinding> findings = bigramInstances.entrySet().stream()
                .map(e -> new SequenceFinding(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream().sorted().limit(5).toList(),
                        e.getValue().size() >= MIN_INSTANCE_COUNT))
                .sorted(Comparator.comparingInt(SequenceFinding::instanceCount).reversed())
                .toList();

        long spanDays = earliest != null && latest != null
                ? Duration.between(earliest, latest).toDays()
                : 0;
        boolean spanSufficient = spanDays >= MIN_SPAN_DAYS;
        boolean demandTriggerFired = spanSufficient && findings.stream().anyMatch(SequenceFinding::meetsThreshold);

        return new RemediationDemandAnalysis(
                earliest, latest, spanDays, spanSufficient, scanned, truncated, findings, demandTriggerFired);
    }

    private static void recordBigrams(Map<List<String>, Set<String>> bigramInstances, String key, List<String> verbs) {
        if (key == null || verbs.size() < MIN_SEQUENCE_LENGTH) {
            return;
        }
        for (int i = 0; i + 1 < verbs.size(); i++) {
            List<String> bigram = List.of(verbs.get(i), verbs.get(i + 1));
            bigramInstances.computeIfAbsent(bigram, k -> new LinkedHashSet<>()).add(key);
        }
    }

    /** One candidate remediation sequence and how many distinct instances exhibited it. */
    public record SequenceFinding(
            List<String> verbs, int instanceCount, List<String> sampleInstances, boolean meetsThreshold) {}

    /**
     * The full S0 verdict. {@code demandTriggerFired} is the single answer R-GOV-08 asks
     * for: a sufficient data span AND at least one sequence over the instance threshold.
     */
    public record RemediationDemandAnalysis(
            Instant dataSpanStart,
            Instant dataSpanEnd,
            long dataSpanDays,
            boolean spanSufficient,
            long scannedRows,
            boolean truncated,
            List<SequenceFinding> sequences,
            boolean demandTriggerFired) {}
}
