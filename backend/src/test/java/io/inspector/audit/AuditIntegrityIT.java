package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.audit.AuditChainVerifier.AuditIntegrityReport;
import io.inspector.audit.AuditChainVerifier.AuditIntegrityReport.FindingType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Issue #304, rung 4 for {@link AuditChainVerifier}: the whole slice end-to-end against a REAL
 * Postgres 16 — proves what {@link AuditChainVerifierTest}'s mocked repository cannot: a genuinely
 * append-only-guarded table, a real {@link AuditRetentionPurger} partition drop with its real
 * checkpoint config event, and a tamper written through a connection that BYPASSES the append-only
 * trigger the way a truly privileged actor would (a plain {@code UPDATE} as the app role is already
 * rejected by the grant layer — {@code AuditRoleGrantsIT} — and by the trigger itself; a superuser
 * disables the trigger first, which only a superuser CAN do).
 *
 * <p>Method order matters: each test builds on the real chain state the previous one left behind
 * (this IS the point — verifying a live, continuously-written chain). {@code
 * @TestMethodOrder(OrderAnnotation)} makes that explicit rather than relying on JUnit's
 * unspecified default. LOCAL-ONLY like the other Testcontainers-backed audit ITs; wired into
 * {@code nightly.yml}'s {@code container-its} job.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d (context boot only).
 */
@SpringBootTest(properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditIntegrityIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AuditService audit;

    @Autowired
    AuditEntryRepository auditRepo;

    @Autowired
    AuditChainVerifier verifier;

    @Autowired
    AuditRetentionPurger purger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Order(1)
    void anIntactChainWrittenThroughTheRealAuditServiceVerifiesCleanly() {
        // The context itself already wrote real config events at startup (MappingSeeder etc.) —
        // this asserts the WHOLE real chain from genesis is clean, not just these new rows. Each
        // carries a real JSON payload (not null) — the exact case the jsonb-canonicalization fix
        // (see AuditChainVerifier's javadoc) exists for.
        for (int i = 0; i < 3; i++) {
            audit.recordConfigEvent("it-marker", "system", true, Map.of("i", i, "test", "AuditIntegrityIT#order1"));
        }

        AuditIntegrityReport report = verifier.verify();

        assertThat(report.intact()).isTrue();
        assertThat(report.firstProblemSeq()).isNull();
        assertThat(report.findings()).isEmpty();
        assertThat(report.rowsChecked()).isGreaterThanOrEqualTo(3);
        assertThat(report.rowCapHit()).isFalse();
    }

    @Test
    @Order(2)
    void aRowMutatedViaADirectSuperuserConnectionIsDetectedAtTheExactSeqAndDoesNotCascade() {
        audit.recordConfigEvent("it-marker", "system", true, Map.of("test", "about-to-be-tampered"));
        long tamperedSeq = auditRepo.findTopByOrderBySeqDesc().orElseThrow().getSeq();

        // A raw app-role UPDATE is already rejected by the grant layer (AuditRoleGrantsIT) AND by
        // the append-only trigger — this test's `jdbc` connects as the Testcontainers superuser
        // (no audit-roles.sql applied here, mirroring AuditRetentionPurgerIT), and even a superuser
        // must explicitly disable the trigger first: exactly the "more privileged than the app
        // role" actor issue #304 is about. The chain_hash column itself is left untouched — a
        // tamper that DOESN'T recompute the hash, which is the only kind this unkeyed chain can
        // ever catch.
        jdbc.execute("ALTER TABLE audit_entry DISABLE TRIGGER audit_entry_append_only");
        int updated = jdbc.update("UPDATE audit_entry SET actor = 'mallory' WHERE seq = ?", tamperedSeq);
        jdbc.execute("ALTER TABLE audit_entry ENABLE TRIGGER audit_entry_append_only");
        assertThat(updated).isEqualTo(1);

        // One more real, untouched row written AFTER the tamper — proves the walk does not
        // cascade a false mismatch onto rows that come after the tampered one.
        audit.recordConfigEvent("it-marker", "system", true, Map.of("test", "written-after-the-tamper"));
        long afterSeq = auditRepo.findTopByOrderBySeqDesc().orElseThrow().getSeq();

        AuditIntegrityReport report = verifier.verify();

        assertThat(report.intact()).isFalse();
        assertThat(report.firstProblemSeq()).isEqualTo(tamperedSeq);
        assertThat(report.findings())
                .filteredOn(f -> f.type() == FindingType.HASH_MISMATCH)
                .singleElement()
                .satisfies(f -> assertThat(f.seq()).isEqualTo(tamperedSeq));
        assertThat(report.findings())
                .as("the row written after the tamper must not itself be flagged")
                .noneMatch(f -> f.seq() == afterSeq);
    }

    @Test
    @Order(3)
    void aCarvedRetentionPurgeBoundaryDoesNotFalsePositive() {
        AuditEntry headBefore = auditRepo.findTopByOrderBySeqDesc().orElseThrow();

        // A synthetic row in an aged-out monthly partition, genuinely chained onto the real head —
        // exactly what a real historical row would look like, just fabricated so the test doesn't
        // need to wait 400+ days. `seq` continues from the real global sequence (untouched by the
        // fabricated `ts`), so this slots into the real chain precisely, like V10's carve.
        jdbc.execute("CREATE TABLE IF NOT EXISTS audit_entry_2019_06 PARTITION OF audit_entry"
                + " FOR VALUES FROM ('2019-06-01') TO ('2019-07-01')");
        UUID oldId = UUID.randomUUID();
        Instant oldTs = Instant.parse("2019-06-15T09:00:00Z");
        AuditEntry synthetic = new AuditEntry(
                oldId, "corr-old", "system", oldTs, "engine-a", null, null, "retry-job", null, null, null, false);
        String oldHash = AuditService.chainHash(headBefore.getChainHash(), synthetic);
        jdbc.update(
                "INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome, chain_hash)"
                        + " VALUES (?, 'corr-old', 'system', ?, 'engine-a', 'retry-job', 'ok', ?)",
                oldId,
                Timestamp.from(oldTs),
                oldHash);
        long oldSeq = jdbc.queryForObject("SELECT seq FROM audit_entry WHERE id = ?", Long.class, oldId);

        // The real next row in the chain — its hash genuinely depends on the synthetic row above
        // being the current head, exactly like the real AuditRetentionPurger checkpoint scenario.
        audit.recordConfigEvent("it-marker", "system", true, Map.of("test", "first-survivor"));
        AuditEntry survivor = auditRepo.findTopByOrderBySeqDesc().orElseThrow();
        assertThat(survivor.getSeq()).isGreaterThan(oldSeq);

        purger.purge(); // real orchestrator: checkpoints the boundary, then DROPs audit_entry_2019_06
        assertThat(Boolean.TRUE.equals(
                        jdbc.queryForObject("SELECT to_regclass('audit_entry_2019_06') IS NULL", Boolean.class)))
                .as("the aged partition must actually be gone")
                .isTrue();

        AuditIntegrityReport report = verifier.verify();

        assertThat(report.findings())
                .as("the purge boundary must be recognized via the checkpoint, never an unexplained gap")
                .filteredOn(f -> f.seq() == survivor.getSeq())
                .singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(FindingType.RETENTION_BOUNDARY));
        assertThat(report.findings()).noneMatch(f -> f.type() == FindingType.UNEXPLAINED_GAP);
    }
}
