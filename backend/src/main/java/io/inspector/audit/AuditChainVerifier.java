package io.inspector.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Issue #304: the chain-walk verifier the tamper-evidence claim never had. {@code chainHash()}
 * (see {@link AuditService}) is written on every row but never READ back and recomputed anywhere
 * in the codebase — a hash chain nobody walks detects nothing. This is that walk.
 *
 * <p><b>What it proves, and what it doesn't.</b> The chain is plain unkeyed SHA-256 over each
 * row's insert-time columns + the previous row's hash (R-AUD-02). Recomputing it forward and
 * comparing to the stored value detects any row whose insert-time columns were altered by an
 * actor who did NOT also recompute every hash from that point forward — i.e. tampering by anyone
 * who cannot reach the hash function, which in practice means anyone without direct superuser
 * write access to this Postgres. It does NOT detect a fully-privileged actor who tampers a row
 * AND correctly recomputes the forward chain to match — an unkeyed same-table hash can always be
 * reproduced by whoever can already write the table it lives in. HMAC keying would close that
 * gap; it is explicitly out of scope here (issue #311, PO-gated design work) — see
 * DATA-CLASSIFICATION.md §2 and OPERATIONS.md §6 for the qualified claim this verifier now backs.
 *
 * <p><b>The walk.</b> Rows are read in {@code seq} order (a single global sequence, immune to
 * which monthly partition a row lives in — V10) via keyset pagination, bounded batch size, never
 * a full in-memory load (this table can be large — R-TEST-10). For each row the expected hash is
 * {@code chainHash(previousRow's ACTUAL stored chain_hash, row)} — deliberately the row's stored
 * predecessor hash, not a recomputed one, so a single tampered row is flagged at EXACTLY its own
 * seq and the walk still tracks the real (untampered) chain forward from there, rather than
 * cascading a false positive onto every later row.
 *
 * <p><b>Partition/retention boundaries (AuditRetentionPurger, V11).</b> A retention purge drops
 * whole aged-out monthly partitions, which can leave a gap in the surviving {@code seq}
 * sequence — the dropped rows' predecessor data is gone, so their successor's hash can no longer
 * be recomputed from scratch. {@code AuditRetentionPurger.checkpointPayload} records exactly this
 * boundary (the first surviving row's seq + its own chain_hash) as an AUDITED config event BEFORE
 * the drop. On a seq gap, this verifier looks up that checkpoint: a match proves the gap is the
 * expected, audited retention boundary, not silent data loss — anything else is an unexplained gap
 * and is reported as a finding, never silently accepted.
 *
 * <p><b>The jsonb re-serialization trap (found writing this class's own IT).</b> {@code
 * chainHash} folds {@code payload} into its material as the exact string {@link AuditService}
 * held BEFORE the row was ever sent to Postgres — Jackson's compact form, e.g. {@code
 * {"i":2,"test":"x"}}. But {@code payload} is a {@code jsonb} column (V1), and jsonb does not
 * preserve input whitespace: reading it back always yields Postgres's own canonical text, e.g.
 * {@code {"i": 2, "test": "x"}} — a spaced-out but semantically identical string (confirmed by
 * {@code AuditRetentionPurgerIT}'s own "match on tokens, not exact JSON" comment). Recomputing the
 * hash straight off a freshly-loaded row's {@code payload} therefore mismatches EVERY row that
 * carries one, always, tampered or not — a false-positive-everything bug, not a security finding.
 * Changing {@code chainHash} itself to hash the jsonb-canonical form would invalidate every
 * already-written row's hash on every live deployment (backward-incompatible, out of scope for
 * #304) — so instead this class re-derives, per row, the SAME compact text {@code AuditService}
 * originally hashed: parse the stored (canonical) JSON back into a value tree and re-serialize it
 * with the SAME {@link com.fasterxml.jackson.databind.ObjectMapper} bean {@code AuditService}
 * uses. jsonb preserves object key order and value formatting for the plain scalar/nested-map
 * payloads this app ever writes, so the round trip reconstructs the original bytes exactly.
 *
 * <p><b>The timestamp-precision trap (same discovery).</b> {@code chainHash} also folds in {@code
 * ts.toString()} — but {@code Clock.systemUTC()} (see {@code TimeConfig}) can return genuine
 * sub-microsecond digits, while {@code timestamptz} (V1) only stores microsecond precision;
 * Postgres silently rounds the rest away on INSERT. Unlike the payload case this is NOT
 * recoverable by reformatting on read — the sub-microsecond digits are gone, not just
 * reformatted, the instant the row lands. {@code AuditService.now()} truncates the clock read to
 * microseconds BEFORE it is used to build the entry (both the {@code ts} column and the hash
 * material), so what gets hashed and what Postgres will durably store are identical going
 * forward. Rows written before that fix may show a {@code HASH_MISMATCH} that is this artifact,
 * not tampering — see {@code AuditIntegrityIT} and RUNBOOK for the operational note.
 */
@Service
public class AuditChainVerifier {

    /** Keyset page size — a bounded batch, never the whole table at once. */
    static final int BATCH_SIZE = 500;

    /**
     * Hard ceiling on rows walked in one call (mirrors {@code RemediationDemandAnalysisService}'s
     * scan-cap doctrine) — a multi-year chain must never make one verification run unbounded.
     * {@link AuditIntegrityReport#rowCapHit()} tells the caller the walk was cut short.
     */
    static final long DEFAULT_ROW_CAP = 2_000_000L;

    /**
     * Cap on how many findings are MATERIALIZED in the returned report — a badly corrupted chain
     * must not itself become an unbounded in-memory list. {@link AuditIntegrityReport#findingsTotal()}
     * still counts every one found; {@link AuditIntegrityReport#findingsTruncated()} says whether
     * the list was cut.
     */
    static final int MAX_FINDINGS_RECORDED = 200;

    private final AuditEntryRepository repository;
    private final ObjectMapper mapper;
    private final long rowCap;

    @Autowired
    public AuditChainVerifier(AuditEntryRepository repository, ObjectMapper mapper) {
        this(repository, mapper, DEFAULT_ROW_CAP);
    }

    /** Package-visible: lets rung-1 tests exercise the row-cap/truncation path without millions of fixture rows. */
    AuditChainVerifier(AuditEntryRepository repository, ObjectMapper mapper, long rowCap) {
        this.repository = repository;
        this.mapper = mapper;
        this.rowCap = rowCap;
    }

    public AuditIntegrityReport verify() {
        long previousSeq = 0; // virtual "row 0" — a genuinely first-ever row (seq 1) chains from genesis.
        String previousHash = AuditService.CHAIN_GENESIS;
        long rowsChecked = 0;
        long findingsTotal = 0;
        boolean rowCapHit = false;
        TreeSet<String> partitions = new TreeSet<>();
        List<AuditIntegrityReport.Finding> findings = new ArrayList<>();

        batchLoop:
        while (true) {
            List<AuditEntry> batch =
                    repository.findBySeqGreaterThanOrderBySeqAsc(previousSeq, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            for (AuditEntry row : batch) {
                if (rowsChecked >= rowCap) {
                    rowCapHit = true;
                    break batchLoop;
                }
                rowsChecked++;
                partitions.add(partitionKey(row));

                long seq = row.getSeq();
                if (seq != previousSeq + 1) {
                    findingsTotal++;
                    addFinding(findings, gapFinding(seq, previousSeq, row));
                } else {
                    String expected = AuditService.chainHash(previousHash, canonicalize(row));
                    if (!expected.equals(row.getChainHash())) {
                        findingsTotal++;
                        addFinding(
                                findings,
                                new AuditIntegrityReport.Finding(
                                        seq,
                                        previousSeq,
                                        AuditIntegrityReport.FindingType.HASH_MISMATCH,
                                        "recomputed chain_hash does not match the stored value at seq " + seq));
                    }
                }
                // Advance using the row's OWN stored hash (not the recomputed "correct" one) — the
                // real writer read this exact value as the chain head for whatever comes next, so
                // continuing the walk from it is what makes a single tampered row NOT cascade into
                // every later row falsely mismatching too.
                previousHash = row.getChainHash();
                previousSeq = seq;
            }
        }

        boolean intact =
                findings.stream().allMatch(f -> f.type() == AuditIntegrityReport.FindingType.RETENTION_BOUNDARY);
        Long firstProblemSeq = findings.stream()
                .filter(f -> f.type() != AuditIntegrityReport.FindingType.RETENTION_BOUNDARY)
                .map(AuditIntegrityReport.Finding::seq)
                .findFirst()
                .orElse(null);

        return new AuditIntegrityReport(
                intact,
                rowsChecked,
                partitions.size(),
                previousSeq,
                firstProblemSeq,
                findingsTotal,
                List.copyOf(findings),
                findingsTotal > findings.size(),
                rowCapHit);
    }

    /** A seq gap: an audited, checked-out retention boundary, or an unexplained hole. */
    private AuditIntegrityReport.Finding gapFinding(long seq, long previousSeq, AuditEntry row) {
        Optional<String> checkpointHash = repository.findRetentionCheckpointHashForFirstSurvivingSeq(seq);
        boolean verifiedBoundary =
                checkpointHash.isPresent() && checkpointHash.get().equals(row.getChainHash());
        return new AuditIntegrityReport.Finding(
                seq,
                previousSeq,
                verifiedBoundary
                        ? AuditIntegrityReport.FindingType.RETENTION_BOUNDARY
                        : AuditIntegrityReport.FindingType.UNEXPLAINED_GAP,
                verifiedBoundary
                        ? "seq gap after " + previousSeq + " matches the retention-purge checkpoint recorded"
                                + " before the drop (AuditRetentionPurger) — an expected, audited partition"
                                + " boundary, not tampering"
                        : "seq gap after " + previousSeq + " (next surviving row is seq " + seq + ") has NO"
                                + " matching retention-purge checkpoint — unexplained, investigate");
    }

    /**
     * Rebuilds the EXACT string {@link AuditService} originally hashed for {@code row}'s payload
     * (see this class's javadoc "jsonb re-serialization trap"): parse the stored jsonb-canonical
     * text back into a value tree, then re-serialize it compactly with the same {@link
     * ObjectMapper} — reconstructing Jackson's original pre-storage bytes. A payload that fails to
     * parse (should never happen — this app only ever writes what it itself serialized) falls back
     * to the raw stored text unchanged, so a genuinely corrupt payload still surfaces as a mismatch
     * rather than being silently waved through.
     */
    private AuditEntry canonicalize(AuditEntry row) {
        String stored = row.getPayload();
        if (stored == null) {
            return row;
        }
        String canonical = reserializeCompact(stored);
        if (Objects.equals(canonical, stored)) {
            return row; // already compact (e.g. "{}") — nothing to rebuild
        }
        AuditEntry copy = new AuditEntry(
                row.getId(),
                row.getCorrelationId(),
                row.getActor(),
                row.getTs(),
                row.getEngineId(),
                row.getTenantId(),
                row.getInstanceId(),
                row.getAction(),
                row.getReason(),
                row.getTicketId(),
                canonical,
                row.isBreakGlass());
        return copy;
    }

    private String reserializeCompact(String jsonbText) {
        try {
            Object parsed = mapper.readValue(jsonbText, Object.class);
            return mapper.writeValueAsString(parsed);
        } catch (JsonProcessingException e) {
            return jsonbText;
        }
    }

    private static void addFinding(List<AuditIntegrityReport.Finding> findings, AuditIntegrityReport.Finding finding) {
        if (findings.size() < MAX_FINDINGS_RECORDED) {
            findings.add(finding);
        }
    }

    private static String partitionKey(AuditEntry row) {
        return YearMonth.from(row.getTs().atZone(ZoneOffset.UTC)).toString();
    }

    /**
     * The verification outcome. {@code intact} is true iff every finding is a
     * {@link FindingType#RETENTION_BOUNDARY} (an expected, audited gap) — a single
     * {@code HASH_MISMATCH} or {@code UNEXPLAINED_GAP} flips it false. {@code firstProblemSeq} is
     * the seq of the first REAL problem (excludes retention boundaries), null when intact.
     */
    public record AuditIntegrityReport(
            boolean intact,
            long rowsChecked,
            int partitionsSpanned,
            long lastSeqChecked,
            Long firstProblemSeq,
            long findingsTotal,
            List<Finding> findings,
            boolean findingsTruncated,
            boolean rowCapHit) {

        public enum FindingType {
            /** A row's stored chain_hash does not match what recomputing it from its actual predecessor yields. */
            HASH_MISMATCH,
            /** A seq gap with no matching, audited retention-purge checkpoint. */
            UNEXPLAINED_GAP,
            /** A seq gap that matches an AuditRetentionPurger checkpoint — expected, not an anomaly. */
            RETENTION_BOUNDARY
        }

        public record Finding(long seq, long previousSeq, FindingType type, String detail) {}
    }
}
