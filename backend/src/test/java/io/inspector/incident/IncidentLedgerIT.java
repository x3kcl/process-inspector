package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.dto.ErrorGroup;
import io.inspector.snapshot.AggregationSample;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The R-BAU-10 substrate against a REAL Postgres 16 (Testcontainers): V18 migrates and
 * Hibernate VALIDATES it (partitioned PK included, via {@code extra_physical_table_types}),
 * a synthetic {@link AggregationSample} ingest writes all three tables, the occurrence
 * {@code ON CONFLICT} upsert is idempotent against the partitioned business-key PK, the
 * create-ahead partitions exist, and — the concurrency doctrine — a resolve interleaved
 * mid-cycle makes the sampler's state-conditional writes MISS (skip) rather than clobber,
 * with the full RESOLVED → zero-state → REGRESSED arc landing episode + config-event audit.
 *
 * <p>Synthetic samples are ingested directly (the ledger is a pure DB-side consumer — no
 * engine needed, unlike {@code SnapshotSamplerIT}); signature hashes are per-run UUIDs so a
 * live dev stack polluting the store via the startup sample can never break assertions.
 * LOCAL-ONLY (failsafe/*IT — not in ci.yml's itClass), like the other DB-backed ITs.
 */
// snapshot sampler OFF: no background cycle may interleave with the deterministic synthetic
// ingests below (the zero-state sweep of a REAL cycle would arm gates mid-test); the ledger
// itself stays enabled — it is a pure event consumer, driven here by direct ingest calls.
@SpringBootTest(properties = {"ENGINE_A_PASSWORD=test", "inspector.snapshot.enabled=false"})
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentLedgerIT {

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
    IncidentLedgerService ledger;

    @Autowired
    IncidentRepository incidents;

    @Autowired
    IncidentEpisodeRepository episodes;

    @Autowired
    IncidentOccurrenceRepository occurrences;

    @Autowired
    IncidentOccurrencePartitionMaintainer maintainer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v18MigratedAndTheMaintainerCreatedTheMonthlyPartitionsAheadOfTheDefault() {
        // Context boot already proved ddl-auto=validate against V18 (partitioned PK included).
        maintainer.maintain(); // idempotent — also ran at ApplicationReadyEvent

        YearMonth thisMonth = YearMonth.from(LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC));
        assertThat(childPartitions())
                .contains(
                        "incident_occurrence_y"
                                + String.format("%04dm%02d", thisMonth.getYear(), thisMonth.getMonthValue()),
                        "incident_occurrence_y"
                                + String.format(
                                        "%04dm%02d",
                                        thisMonth.plusMonths(1).getYear(),
                                        thisMonth.plusMonths(1).getMonthValue()),
                        "incident_occurrence_default");
    }

    @Test
    void aSyntheticIngestWritesAllThreeTables() {
        String hash = "it-" + UUID.randomUUID();
        Instant seen = Instant.parse("2026-07-18T09:00:37Z");
        Instant bucket = Instant.parse("2026-07-18T09:00:00Z");

        ledger.ingest(sample(seen, group(hash, 7, 5, 2)), bucket);

        Incident row = incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(row.getState()).isEqualTo(IncidentState.OPEN);
        assertThat(row.getLastTotal()).isEqualTo(7);
        assertThat(row.getCountsByEngine()).contains("engine-a");

        List<IncidentEpisode> eps = episodes.findByIncidentIdOrderByStartedAtDesc(row.getId());
        assertThat(eps).singleElement().satisfies(ep -> {
            assertThat(ep.getStartState()).isEqualTo(IncidentState.OPEN);
            assertThat(ep.getEndedAt()).isNull();
            assertThat(ep.getPeakTotal()).isEqualTo(7);
        });

        assertThat(occurrences.findByIdIncidentIdOrderByIdSampledAtAsc(row.getId()))
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.getId().getSampledAt()).isEqualTo(bucket);
                    assertThat(o.getTotal()).isEqualTo(7);
                    assertThat(o.getDeadLetterCount()).isEqualTo(5);
                    assertThat(o.getRetryingCount()).isEqualTo(2);
                    assertThat(o.isTruncated()).isFalse();
                });
    }

    @Test
    void theOccurrenceUpsertIsIdempotentAgainstThePartitionedBusinessKeyPk() {
        String hash = "it-" + UUID.randomUUID();
        Instant bucket = Instant.parse("2026-07-18T10:00:00Z");

        ledger.ingest(sample(bucket, group(hash, 5, 5, 0)), bucket);
        ledger.ingest(sample(bucket.plusSeconds(20), group(hash, 9, 8, 1)), bucket); // same bucket, re-fire

        long id = incidents
                .findBySignatureHashAndAlgoVersion(hash, 1)
                .orElseThrow()
                .getId();
        assertThat(occurrences.findByIdIncidentIdOrderByIdSampledAtAsc(id))
                .singleElement()
                .satisfies(o -> assertThat(o.getTotal()).isEqualTo(9)); // latest observation wins

        // the live episode peak is monotonic — the GREATEST bump kept the max
        assertThat(episodes.findFirstByIncidentIdAndEndedAtIsNullOrderByStartedAtDesc(id)
                        .orElseThrow()
                        .getPeakTotal())
                .isEqualTo(9);
    }

    @Test
    void aMidCycleResolveMakesSamplerWritesMissThenTheFullRegressionArcLands() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-07-18T11:00:00Z");

        // 1. first sighting → OPEN
        ledger.ingest(sample(t0, group(hash, 6, 6, 0)), t0);
        Incident row = incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();

        // 2. a human resolves mid-cycle (S3's effect, simulated at the store): episode closed,
        //    state RESOLVED, gate disarmed
        jdbc.update(
                "UPDATE incident_episode SET ended_at = now(), resolved_by = 'it-operator',"
                        + " resolve_reason = 'fixed by config rollout' WHERE incident_id = ? AND ended_at IS NULL",
                row.getId());
        jdbc.update(
                "UPDATE incident SET state = 'RESOLVED', seen_zero_since_resolve = false, version = version + 1"
                        + " WHERE id = ?",
                row.getId());

        // 3. the stale-state conditional write MISSES (skip, not clobber) — the race doctrine
        assertThat(incidents.updateObservedTotals(row.getId(), "OPEN", t0.plusSeconds(60), 99, false, "{}"))
                .isZero();

        // 4. the group is still live this cycle (cache/retry lag) — NO regression (zombie killer),
        //    but the totals stay honest
        Instant t1 = t0.plusSeconds(60);
        ledger.ingest(sample(t1, group(hash, 4, 4, 0)), t1);
        Incident afterLag = incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(afterLag.getState()).isEqualTo(IncidentState.RESOLVED);
        assertThat(afterLag.getRegressionCount()).isZero();
        assertThat(afterLag.getLastTotal()).isEqualTo(4);

        // 5. a cycle observes the class absent → the zero-state gate arms
        Instant t2 = t0.plusSeconds(120);
        ledger.ingest(sample(t2), t2);
        assertThat(incidents
                        .findBySignatureHashAndAlgoVersion(hash, 1)
                        .orElseThrow()
                        .isSeenZeroSinceResolve())
                .isTrue();

        // 6. the class returns → RESOLVED → REGRESSED: new episode + config-event audit row
        Instant t3 = t0.plusSeconds(180);
        ledger.ingest(sample(t3, group(hash, 3, 3, 0)), t3);

        Incident regressed =
                incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(regressed.getState()).isEqualTo(IncidentState.REGRESSED);
        assertThat(regressed.getRegressionCount()).isEqualTo(1);
        assertThat(regressed.getLastRegressedAt()).isEqualTo(t3);
        assertThat(regressed.isSeenZeroSinceResolve()).isFalse();

        List<IncidentEpisode> eps = episodes.findByIncidentIdOrderByStartedAtDesc(regressed.getId());
        assertThat(eps).hasSize(2);
        assertThat(eps.get(0).getStartState()).isEqualTo(IncidentState.REGRESSED);
        assertThat(eps.get(0).getEndedAt()).isNull();
        assertThat(eps.get(1).getEndedAt()).isNotNull();

        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'incident-regressed' AND payload::text LIKE ?",
                Integer.class,
                "%" + hash + "%");
        assertThat(auditRows).isEqualTo(1);
    }

    private List<String> childPartitions() {
        return jdbc.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'incident_occurrence'
                """, String.class);
    }

    private static AggregationSample sample(Instant sampledAt, ErrorGroup... groups) {
        return new AggregationSample(List.of(), List.of(groups), sampledAt, Set.of());
    }

    private static ErrorGroup group(String hash, long total, long deadLetters, long retrying) {
        return new ErrorGroup(
                hash,
                1,
                "java.net.SocketTimeoutException",
                "timeout after # ms",
                "timeout after 5000 ms",
                total,
                deadLetters,
                retrying,
                Map.of("engine-a", Map.of("order:v3", total)));
    }
}
