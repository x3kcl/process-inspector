package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditChainVerifier.AuditIntegrityReport;
import io.inspector.audit.AuditChainVerifier.AuditIntegrityReport.FindingType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Rung 1 for the issue #304 chain-walk verifier: no Postgres, a mocked {@link
 * AuditEntryRepository} stands in for the {@code seq}-ordered table. {@link AuditIntegrityIT}
 * proves the same logic against a REAL chain (real superuser tampering, a real
 * {@code AuditRetentionPurger} drop).
 */
class AuditChainVerifierTest {

    private final AuditEntryRepository repository = mock(AuditEntryRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wires the mock to serve keyset batches off a plain in-memory list, like a real seq index would. */
    private void seed(List<AuditEntry> rows) {
        when(repository.findBySeqGreaterThanOrderBySeqAsc(anyLong(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    long afterSeq = inv.getArgument(0);
                    Pageable page = inv.getArgument(1);
                    return rows.stream()
                            .filter(r -> r.getSeq() > afterSeq)
                            .sorted(Comparator.comparingLong(AuditEntry::getSeq))
                            .limit(page.getPageSize())
                            .toList();
                });
    }

    private AuditChainVerifier verifier() {
        return new AuditChainVerifier(repository, mapper);
    }

    @Test
    void anIntactChainVerifiesWithNoFindings() {
        List<AuditEntry> rows = chainOf(5);
        seed(rows);
        when(repository.findRetentionCheckpointHashForFirstSurvivingSeq(anyLong()))
                .thenReturn(Optional.empty());

        AuditIntegrityReport report = verifier().verify();

        assertThat(report.intact()).isTrue();
        assertThat(report.rowsChecked()).isEqualTo(5);
        assertThat(report.lastSeqChecked()).isEqualTo(5);
        assertThat(report.firstProblemSeq()).isNull();
        assertThat(report.findingsTotal()).isZero();
        assertThat(report.findings()).isEmpty();
        assertThat(report.rowCapHit()).isFalse();
    }

    @Test
    void aTamperedRowIsDetectedAtItsOwnSeqAndDoesNotCascadeToLaterRows() {
        List<AuditEntry> rows = chainOf(5);
        // Mimic a direct-superuser tamper: row 3's `actor` field changes AFTER the fact, but its
        // chain_hash column is left as originally written (a naive tamper never recomputes it).
        AuditEntry tampered = rows.get(2);
        AuditEntry replaced = withActor(tampered, "mallory");
        rows.set(2, replaced);
        seed(rows);
        when(repository.findRetentionCheckpointHashForFirstSurvivingSeq(anyLong()))
                .thenReturn(Optional.empty());

        AuditIntegrityReport report = verifier().verify();

        assertThat(report.intact()).isFalse();
        assertThat(report.firstProblemSeq()).isEqualTo(3);
        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.HASH_MISMATCH);
            assertThat(f.seq()).isEqualTo(3);
        });
        assertThat(report.rowsChecked()).isEqualTo(5);
    }

    @Test
    void anUnexplainedSeqGapWithNoCheckpointIsFlaggedAsAnAnomaly() {
        // Rows 1, 2 present; row 5 is next — seq 3/4 vanished with no retention-purge checkpoint.
        List<AuditEntry> rows = new ArrayList<>(chainOf(2));
        rows.add(rowAfterGap(5, rows.get(1).getChainHash()));
        seed(rows);
        when(repository.findRetentionCheckpointHashForFirstSurvivingSeq(5L)).thenReturn(Optional.empty());

        AuditIntegrityReport report = verifier().verify();

        assertThat(report.intact()).isFalse();
        assertThat(report.firstProblemSeq()).isEqualTo(5);
        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.UNEXPLAINED_GAP);
            assertThat(f.seq()).isEqualTo(5);
            assertThat(f.previousSeq()).isEqualTo(2);
        });
    }

    @Test
    void aRetentionPurgeCheckpointBoundaryDoesNotFalsePositive() {
        // Rows 1..seq-of-the-old-partition were dropped by a real purge; the surviving chain
        // starts at seq 7, whose OWN stored chain_hash was computed (at write time, before the
        // purge) against a predecessor that no longer exists. The checkpoint AuditRetentionPurger
        // wrote right before the DROP recorded exactly this seq + hash as the boundary.
        AuditEntry firstSurvivor = rowAfterGap(7, "whatever-the-real-predecessor-hash-was");
        List<AuditEntry> rows = new ArrayList<>();
        rows.add(firstSurvivor);
        rows.addAll(continueChainFrom(firstSurvivor, 2)); // seq 8, 9
        seed(rows);
        when(repository.findRetentionCheckpointHashForFirstSurvivingSeq(7L))
                .thenReturn(Optional.of(firstSurvivor.getChainHash()));

        AuditIntegrityReport report = verifier().verify();

        assertThat(report.intact())
                .as("a checkpoint-verified retention boundary must not fail the chain")
                .isTrue();
        assertThat(report.firstProblemSeq()).isNull();
        assertThat(report.findings())
                .singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(FindingType.RETENTION_BOUNDARY));
        assertThat(report.rowsChecked()).isEqualTo(3);
        assertThat(report.lastSeqChecked()).isEqualTo(9);
    }

    @Test
    void anUncheckpointedGapAtTheVeryStartOfTheChainIsAlsoFlagged() {
        // The genesis row itself is missing (seq starts at 4) and no checkpoint explains it.
        AuditEntry firstSurvivor = rowAfterGap(4, "irrelevant");
        seed(List.of(firstSurvivor));
        when(repository.findRetentionCheckpointHashForFirstSurvivingSeq(4L)).thenReturn(Optional.empty());

        AuditIntegrityReport report = verifier().verify();

        assertThat(report.intact()).isFalse();
        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.UNEXPLAINED_GAP);
            assertThat(f.previousSeq()).isEqualTo(0);
            assertThat(f.seq()).isEqualTo(4);
        });
    }

    @Test
    void theRowCapBoundsTheWorkAndFlagsTruncation() {
        List<AuditEntry> rows = chainOf(5);
        seed(rows);

        AuditIntegrityReport report = new AuditChainVerifier(repository, mapper, 3L).verify();

        assertThat(report.rowsChecked()).isEqualTo(3);
        assertThat(report.rowCapHit()).isTrue();
        // A cap cut mid-chain is not itself a tamper finding — it's an honest "didn't look further".
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void partitionsSpannedCountsDistinctMonths() {
        AuditEntry julRow = entry(UUID.randomUUID().toString(), Instant.parse("2026-07-15T00:00:00Z"));
        julRow.setSeq(1L);
        julRow.setChainHash(AuditService.chainHash(AuditService.CHAIN_GENESIS, julRow));
        AuditEntry augRow = entry(UUID.randomUUID().toString(), Instant.parse("2026-08-01T00:00:00Z"));
        augRow.setSeq(2L);
        augRow.setChainHash(AuditService.chainHash(julRow.getChainHash(), augRow));
        List<AuditEntry> rows = List.of(julRow, augRow);
        seed(rows);

        AuditIntegrityReport report = verifier().verify();

        assertThat(report.intact()).isTrue();
        assertThat(report.partitionsSpanned()).isEqualTo(2);
    }

    /* -------------------- fixtures -------------------- */

    /** A genuinely-chained run of {@code count} rows, seq 1..count, from genesis. */
    private static List<AuditEntry> chainOf(int count) {
        List<AuditEntry> rows = new ArrayList<>();
        String previousHash = AuditService.CHAIN_GENESIS;
        for (int i = 1; i <= count; i++) {
            AuditEntry row = entry(UUID.randomUUID().toString(), Instant.parse("2026-07-06T11:00:00Z"));
            row.setSeq((long) i);
            row.setChainHash(AuditService.chainHash(previousHash, row));
            previousHash = row.getChainHash();
            rows.add(row);
        }
        return rows;
    }

    /** A row placed at {@code seq}, its own chain_hash computed against a (possibly gone) predecessor. */
    private static AuditEntry rowAfterGap(long seq, String previousHash) {
        AuditEntry row = entry(UUID.randomUUID().toString(), Instant.parse("2026-07-06T11:00:00Z"));
        row.setSeq(seq);
        row.setChainHash(AuditService.chainHash(previousHash, row));
        return row;
    }

    /** Continues a genuinely-chained run onward from an existing row, {@code count} more rows. */
    private static List<AuditEntry> continueChainFrom(AuditEntry from, int count) {
        List<AuditEntry> rows = new ArrayList<>();
        String previousHash = from.getChainHash();
        long seq = from.getSeq();
        for (int i = 1; i <= count; i++) {
            AuditEntry row = entry(UUID.randomUUID().toString(), Instant.parse("2026-07-06T11:00:00Z"));
            row.setSeq(seq + i);
            row.setChainHash(AuditService.chainHash(previousHash, row));
            previousHash = row.getChainHash();
            rows.add(row);
        }
        return rows;
    }

    /** Same coordinates as {@code source} but a different actor, chain_hash left untouched (a naive tamper). */
    private static AuditEntry withActor(AuditEntry source, String actor) {
        AuditEntry replaced = new AuditEntry(
                source.getId(),
                source.getCorrelationId(),
                actor,
                source.getTs(),
                source.getEngineId(),
                source.getTenantId(),
                source.getInstanceId(),
                source.getAction(),
                source.getReason(),
                source.getTicketId(),
                source.getPayload(),
                source.isBreakGlass());
        replaced.setSeq(source.getSeq());
        replaced.setChainHash(source.getChainHash());
        return replaced;
    }

    private static AuditEntry entry(String id, Instant ts) {
        return new AuditEntry(
                UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "corr",
                "operator",
                ts,
                "engine-a",
                null,
                "pi-1",
                "retry-job",
                null,
                null,
                null,
                false);
    }
}
