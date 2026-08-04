package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.dto.ErrorGroup;
import io.inspector.snapshot.AggregationSample;
import java.time.Instant;
import java.util.HashMap;
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
 * The two NATIVE aggregates the attention model is built from, against a REAL Postgres 16
 * (Testcontainers) — {@code IncidentOccurrenceRepository.arrivalsSince} (a {@code LAG} window
 * function over a PARTITIONED table) and {@code
 * IncidentEpisodeRepository.closedEpisodeDurationSeconds} ({@code EXTRACT(EPOCH …)}).
 *
 * <p>This IT exists because both queries are native and — with
 * {@code inspector.triage.attention-ordering} defaulting false (ALARM-COST-MODEL §7, the gate is
 * NOT met) — nothing on the default path ever executes them. Without a rung-4 test a malformed
 * statement would ship completely undetected and only surface on the day an operator flips the
 * flag. It also pins the two honesty rules the SQL, not the Java, is responsible for: arrivals are
 * positive DELTAS (never levels), and a delta touching a truncated bucket is discarded because a
 * truncated sample is a FLOOR, not a level (R-SEM-12).
 *
 * <p>Synthetic ingests through {@link IncidentLedgerService} — the ledger is a pure DB-side
 * consumer, so no engine is needed. Signature hashes are per-run UUIDs so a live dev stack
 * polluting the store can never break assertions. LOCAL-ONLY ({@code *IT}), like the other
 * DB-backed ITs. Lives in {@code io.inspector.incident} because it drives the two repository
 * queries directly — widening {@code IncidentLedgerService.ingest} to public for a test would be
 * the wrong trade.
 */
@SpringBootTest(properties = {"ENGINE_A_PASSWORD=test", "inspector.snapshot.enabled=false"})
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttentionAggregateQueriesIT {

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
    JdbcTemplate jdbc;

    @Test
    void arrivalsSumThePositiveDeltasAndIgnoreEveryDrain() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-01T09:00:00Z");
        // 5 → 9 (+4) → 3 (drain, ignored) → 11 (+8) → 11 (flat) ⇒ 12 arrivals, NOT the level 11.
        ingest(hash, t0, 5, false);
        ingest(hash, t0.plusSeconds(60), 9, false);
        ingest(hash, t0.plusSeconds(120), 3, false);
        ingest(hash, t0.plusSeconds(180), 11, false);
        ingest(hash, t0.plusSeconds(240), 11, false);

        assertThat(arrivals(hash, t0)).isEqualTo(12);
    }

    @Test
    void aDeltaTouchingATruncatedBucketIsDiscardedBecauseAFloorIsNotALevel() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-02T09:00:00Z");
        // The middle bucket is a TRUNCATED floor of 500. Both deltas that touch it (5→500 and
        // 500→7) are phantoms of the scan cap, not arrivals; only 7→9 (+2) is real.
        ingest(hash, t0, 5, false);
        ingest(hash, t0.plusSeconds(60), 500, true);
        ingest(hash, t0.plusSeconds(120), 7, false);
        ingest(hash, t0.plusSeconds(180), 9, false);

        assertThat(arrivals(hash, t0)).isEqualTo(2);
    }

    @Test
    void aSingleBucketProducesNoArrivalsBecauseThereIsNoPredecessorToDifferenceAgainst() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-03T09:00:00Z");
        ingest(hash, t0, 21, false);

        // The no-post-mint-arrivals convention (ALARM-COST-MODEL §6): the estimator sums DELTAS,
        // so a class's founding burst is not retroactively invented as an arrival.
        assertThat(arrivals(hash, t0)).isZero();
    }

    @Test
    void arrivalsAreScopedToTheWindowAndKeyedPerIncident() {
        String first = "it-" + UUID.randomUUID();
        String second = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-01T12:00:00Z");
        ingest(first, t0, 1, false);
        ingest(first, t0.plusSeconds(60), 4, false); // +3
        ingest(second, t0, 10, false);
        ingest(second, t0.plusSeconds(60), 17, false); // +7

        assertThat(arrivals(first, t0)).isEqualTo(3);
        assertThat(arrivals(second, t0)).isEqualTo(7);
        // A window starting after the whole series sees nothing at all.
        assertThat(arrivals(first, t0.plusSeconds(3600))).isZero();
    }

    @Test
    void onlyClosedEpisodesYieldADurationAndItIsMeasuredInSeconds() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-01T15:00:00Z");
        ingest(hash, t0, 6, false);
        long incidentId = incidentId(hash);

        // Live episode ⇒ absent from the aggregate: "still broken" is not an MTTR observation.
        assertThat(closedDurations(incidentId)).isEmpty();

        jdbc.update(
                "UPDATE incident_episode SET ended_at = started_at + interval '90 minutes',"
                        + " resolved_by = 'it-operator', resolve_reason = 'fixed by config rollout'"
                        + " WHERE incident_id = ? AND ended_at IS NULL",
                incidentId);

        assertThat(closedDurations(incidentId)).containsExactly(5_400L);
    }

    /* ---------------- helpers ---------------- */

    private void ingest(String hash, Instant bucket, long total, boolean truncated) {
        AggregationSample sample = new AggregationSample(
                List.of(), List.of(group(hash, total)), bucket, truncated ? Set.of("engine-a") : Set.of());
        ledger.ingest(sample, bucket);
    }

    private static ErrorGroup group(String hash, long total) {
        return new ErrorGroup(
                hash,
                1,
                "java.net.SocketTimeoutException",
                "timeout after # ms",
                "timeout after 5000 ms",
                total,
                total,
                0,
                Map.of("engine-a", Map.of("order:v3", total)));
    }

    private long incidentId(String hash) {
        return incidents
                .findBySignatureHashAndAlgoVersion(hash, 1)
                .map(Incident::getId)
                .orElseThrow();
    }

    private long arrivals(String hash, Instant since) {
        long incidentId = incidentId(hash);
        Map<Long, Long> byIncident = new HashMap<>();
        for (Object[] row : occurrences.arrivalsSince(since)) {
            byIncident.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return byIncident.getOrDefault(incidentId, 0L);
    }

    private List<Long> closedDurations(long incidentId) {
        return episodes.closedEpisodeDurationSeconds().stream()
                .filter(row -> ((Number) row[0]).longValue() == incidentId)
                .map(row -> ((Number) row[1]).longValue())
                .toList();
    }
}
