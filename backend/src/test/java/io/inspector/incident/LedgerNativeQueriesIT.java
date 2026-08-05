package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.dto.ErrorGroup;
import io.inspector.snapshot.AggregationSample;
import java.time.Duration;
import java.time.Instant;
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
 * The ledger's NATIVE window/aggregate queries against a REAL Postgres 16 (Testcontainers) —
 * {@code IncidentOccurrenceRepository.arrivalsSince} (the attention F factor: a {@code LAG}
 * window function over a PARTITIONED table), {@code
 * IncidentEpisodeRepository.closedEpisodeDurationSeconds} (the M factor, {@code EXTRACT(EPOCH …)})
 * and {@code IncidentOccurrenceRepository.findSpellShapeRowsDescending} (the RETRYING lane's
 * bounded spell substrate: a two-deep {@code LAG} filter mapped back onto the entity).
 *
 * <p>This IT exists because all three are native and none of them runs on a default-flagged,
 * pilot-data path — with {@code inspector.triage.attention-ordering} false (ALARM-COST-MODEL §7,
 * the gate is NOT met) nothing executes the F/M aggregates at all. Without a rung-4 test a
 * malformed statement would ship completely undetected and only surface on the day an operator
 * flips the flag. It also pins the honesty rules the SQL, not the Java, is responsible for:
 * arrivals are positive DELTAS (never levels); a delta touching a truncated bucket is discarded
 * because a truncated sample is a FLOOR, not a level (R-SEM-12); and a delta touching a BLIND
 * bucket is discarded because an unreachable engine is not a drain (#302).
 *
 * <p>Synthetic ingests through {@link IncidentLedgerService} — the ledger is a pure DB-side
 * consumer, so no engine is needed. Signature hashes are per-run UUIDs so a live dev stack
 * polluting the store can never break assertions. LOCAL-ONLY ({@code *IT}), like the other
 * DB-backed ITs. Lives in {@code io.inspector.incident} because it drives the repository
 * queries directly — widening {@code IncidentLedgerService.ingest} to public for a test would be
 * the wrong trade.
 */
@SpringBootTest(properties = {"ENGINE_A_PASSWORD=test", "inspector.snapshot.enabled=false"})
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerNativeQueriesIT {

    /** ISA-18.2's flood window and {@code inspector.triage.attention.burst-window}'s default. */
    private static final Duration BURST_WINDOW = Duration.ofMinutes(10);

    /** Anchors both bins past every fixture, so a pre-#365 assertion sees them empty. */
    private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    /** The steady observation SCOPE (#372, V22) every pre-#372 fixture in this class ingests under. */
    private static final Set<String> FLEET = Set.of("engine-a");

    /** The same pilot after a second engine is ENABLED — a different, larger fleet. */
    private static final Set<String> FLEET_GROWN = Set.of("engine-a", "engine-7");

    /** Scope NOT stated: the V22 fail-closed value, comparable to nothing, itself included. */
    private static final Set<String> FLEET_UNRECORDED = Set.of();

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
        // Birth at 5 (+5, FIX 3) → 9 (+4) → 3 (drain, ignored) → 11 (+8) → 11 (flat)
        // ⇒ 17 arrivals, NOT the level 11.
        ingest(hash, t0, 5, false);
        ingest(hash, t0.plusSeconds(60), 9, false);
        ingest(hash, t0.plusSeconds(120), 3, false);
        ingest(hash, t0.plusSeconds(180), 11, false);
        ingest(hash, t0.plusSeconds(240), 11, false);

        assertThat(arrivals(hash, t0)).isEqualTo(17);
    }

    @Test
    void aDeltaTouchingATruncatedBucketIsDiscardedBecauseAFloorIsNotALevel() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-02T09:00:00Z");
        // Birth at 5 (+5). The next bucket is a TRUNCATED floor of 500: both deltas that touch it
        // (5→500 and 500→7) are phantoms of the scan cap, not arrivals; only 7→9 (+2) is real.
        ingest(hash, t0, 5, false);
        ingest(hash, t0.plusSeconds(60), 500, true);
        ingest(hash, t0.plusSeconds(120), 7, false);
        ingest(hash, t0.plusSeconds(180), 9, false);

        assertThat(arrivals(hash, t0)).isEqualTo(7);
        // Two of the four samples were usable, so the window is PARTIAL, not unknown.
        assertThat(observedSamples(hash, t0)).isEqualTo(4);
        assertThat(trustedSamples(hash, t0)).isEqualTo(2);
    }

    /* ---------------- FIX 3: a class's own birth IS an arrival ---------------- */

    @Test
    void aClassesFirstEverBucketCountsItsWholePopulationAsArriving() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-03T09:00:00Z");
        // The review's confirmed defect: LAG(total) is NULL for the window's first row and the
        // old predicate (`WHERE delta IS NOT NULL`) threw it away — so an incident's FIRST EVER
        // occurrence row, which IS the arrival of its entire population, could never be counted.
        // A bad deploy breaking 5 000 instances at once appeared with total = 5000, stayed flat,
        // and scored arrivals = 0 ⇒ F = 0 ⇒ A = 0: permanently below a class that gained one
        // member. 0 → 5 000 in one bucket is the LARGEST possible arrival event.
        ingest(hash, t0, 5_000, false);
        ingest(hash, t0.plusSeconds(60), 5_000, false); // flat ever after

        assertThat(arrivals(hash, t0)).isEqualTo(5_000);
    }

    @Test
    void aWindowThatMerelyStartsMidLifeDoesNotCountTheStandingPopulationAsArrivals() {
        String hash = "it-" + UUID.randomUUID();
        Instant birth = Instant.parse("2026-08-03T11:00:00Z");
        Instant windowStart = birth.plusSeconds(180);
        // The other half of FIX 3, and the reason it is a JOIN on first_seen rather than a plain
        // COALESCE(LAG(total), 0): only the incident's OWN first row gets the 0 baseline. A
        // window opening mid-life still finds LAG NULL on its first row and still discards it —
        // otherwise every 28-day window would re-bank the standing population as fresh growth,
        // every time, and "arrivals" would silently become "size".
        ingest(hash, birth, 5_000, false);
        ingest(hash, birth.plusSeconds(60), 5_000, false);
        ingest(hash, birth.plusSeconds(120), 5_000, false);
        ingest(hash, windowStart, 5_000, false);
        ingest(hash, windowStart.plusSeconds(60), 5_003, false); // +3, the only real growth

        assertThat(arrivals(hash, windowStart)).isEqualTo(3);
        assertThat(arrivals(hash, birth)).isEqualTo(5_003); // whole life: birth + growth, once
    }

    @Test
    void arrivalsAreScopedToTheWindowAndKeyedPerIncident() {
        String first = "it-" + UUID.randomUUID();
        String second = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-01T12:00:00Z");
        ingest(first, t0, 1, false); // birth: +1
        ingest(first, t0.plusSeconds(60), 4, false); // +3
        ingest(second, t0, 10, false); // birth: +10
        ingest(second, t0.plusSeconds(60), 17, false); // +7

        assertThat(arrivals(first, t0)).isEqualTo(4);
        assertThat(arrivals(second, t0)).isEqualTo(17);
        // A window starting after the whole series sees nothing at all.
        assertThat(arrivals(first, t0.plusSeconds(3600))).isZero();
    }

    /* ---------------- FIX 2: a wholly untrusted window is UNKNOWN, not zero ---------------- */

    @Test
    void aWindowWhoseEverySampleWasTruncatedReportsZeroTrustedSamplesSoTheCallerCanSayUnknown() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-09T09:00:00Z");
        // A big class on an engine PERMANENTLY at its failure-lane scan cap: every bucket is a
        // floor, so every delta is discarded and the sum is 0. Before this fix that 0 was
        // indistinguishable from "measured, and it did not grow" — and F = log2(1+0) = 0 zeroed
        // A(c) = F*R*M*S outright, demoting exactly the largest classes. The counts are what let
        // AttentionScoreService tell the two apart and degrade F to the neutral 1 instead.
        for (int i = 0; i < 5; i++) {
            ingest(hash, t0.plusSeconds(60L * i), 4_000, true);
        }

        assertThat(arrivals(hash, t0)).isZero();
        assertThat(observedSamples(hash, t0)).isEqualTo(5); // birth + 4 differenceable deltas
        assertThat(trustedSamples(hash, t0)).isZero(); // ⇒ arrivalsUnknown
    }

    @Test
    void aWindowWhoseEverySampleWasBlindReportsTheSameUnknownShapeAsAWhollyTruncatedOne() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-09T11:00:00Z");
        for (int i = 0; i < 4; i++) {
            ingest(hash, t0.plusSeconds(60L * i), 900, false, false); // engine unreachable all window
        }

        assertThat(arrivals(hash, t0)).isZero();
        assertThat(observedSamples(hash, t0)).isEqualTo(4);
        assertThat(trustedSamples(hash, t0)).isZero();
    }

    @Test
    void aFullyObservedFlatWindowIsAMEASUREDZeroAndSaysSoWithTrustedSamples() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-09T13:00:00Z");
        // The control case that keeps "unknown" from swallowing "measured, and genuinely flat":
        // a class born before the window whose every in-window sample was clean.
        ingest(hash, t0.minusSeconds(120), 60, false);
        ingest(hash, t0.minusSeconds(60), 60, false);
        for (int i = 0; i < 4; i++) {
            ingest(hash, t0.plusSeconds(60L * i), 60, false);
        }

        assertThat(arrivals(hash, t0)).isZero();
        assertThat(observedSamples(hash, t0)).isEqualTo(3); // 4 rows, first has no in-window predecessor
        assertThat(trustedSamples(hash, t0)).isEqualTo(3); // ⇒ NOT unknown: F stays a real 0
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

    /* ---------------- #302: a blind cycle is not movement ---------------- */

    @Test
    void aDeltaTouchingABlindBucketIsDiscardedBecauseAnUnreachableEngineIsNotADrain() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-05T09:00:00Z");
        // The review's exact outage sequence: a multi-engine class at 1000 loses the engine that
        // hosts 900 of its members for two buckets, then gets it back. Reading the blind rows as
        // observations makes the recovery edge a +900 ARRIVAL — 900 phantom members from a
        // two-minute blip, F jumping log2(1+0)=0 to log2(901)≈9.8, and compounding on every flap.
        // Born (and long settled) BEFORE the window opens, so FIX 3's birth seeding is not in
        // play here — this test is only about the outage edge.
        ingest(hash, t0.minusSeconds(60), 1000, false, true);
        ingest(hash, t0, 1000, false, true);
        ingest(hash, t0.plusSeconds(60), 1000, false, true);
        ingest(hash, t0.plusSeconds(120), 100, false, false); // engine unreachable
        ingest(hash, t0.plusSeconds(180), 100, false, false); // still unreachable
        ingest(hash, t0.plusSeconds(240), 1000, false, true); // back — NOT 900 new members
        ingest(hash, t0.plusSeconds(300), 1000, false, true);

        assertThat(arrivals(hash, t0)).isZero();
    }

    @Test
    void arrivalsAcrossACompleteCycleAreStillCountedAfterABlindStretch() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-06T09:00:00Z");
        // Guard against over-discarding: only deltas TOUCHING a blind row are dropped.
        ingest(hash, t0, 10, false, false); // blind
        ingest(hash, t0.plusSeconds(60), 10, false, true); // discarded (predecessor blind)
        ingest(hash, t0.plusSeconds(120), 14, false, true); // +4, both endpoints observed
        ingest(hash, t0.plusSeconds(180), 20, false, true); // +6

        assertThat(arrivals(hash, t0)).isEqualTo(10);
    }

    /* ---------------- the RETRYING lane's bounded spell substrate ---------------- */

    @Test
    void theSpellShapeQueryReturnsOnlyTheSpellItsClosingZeroAndTheLookaheadNewestFirst() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-07T09:00:00Z");
        // Nine quiet-ish buckets around a two-bucket spell: the 90-day window at a 60s beat is
        // ~129 600 rows per class, so the reduction to shape-relevant rows is the whole point.
        ingestJobs(hash, t0, 5, 5, 0, true);
        ingestJobs(hash, t0.plusSeconds(60), 5, 5, 0, true);
        ingestJobs(hash, t0.plusSeconds(120), 7, 5, 2, true); // spell starts
        ingestJobs(hash, t0.plusSeconds(180), 6, 5, 1, false); // ...and a blind bucket inside it
        ingestJobs(hash, t0.plusSeconds(240), 5, 5, 0, true); // closing zero
        ingestJobs(hash, t0.plusSeconds(300), 5, 5, 0, true); // +1 look-ahead
        ingestJobs(hash, t0.plusSeconds(360), 5, 5, 0, true); // quiet again — must NOT come back
        ingestJobs(hash, t0.plusSeconds(420), 5, 5, 0, true);

        List<IncidentOccurrence> rows = occurrences.findSpellShapeRowsDescending(incidentId(hash), t0, 100);

        assertThat(rows.stream().map(row -> row.getId().getSampledAt()))
                .containsExactly(t0.plusSeconds(300), t0.plusSeconds(240), t0.plusSeconds(180), t0.plusSeconds(120));
        // ...and both honesty markers round-trip onto the entity (V21 mapping + ddl-auto=validate).
        assertThat(rows.stream()
                        .filter(row -> !row.isCycleComplete())
                        .map(row -> row.getId().getSampledAt()))
                .containsExactly(t0.plusSeconds(180));
        assertThat(rows.get(0).getRetryingCount()).isZero();
        assertThat(rows.get(3).getRetryingCount()).isEqualTo(2);
    }

    @Test
    void theSpellShapeQueryCapKeepsTheNEWESTRowsSoTheCutIsLeftCensoredNotArbitrary() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-08T09:00:00Z");
        for (int i = 0; i < 6; i++) {
            ingestJobs(hash, t0.plusSeconds(60L * i), 5, 5, 1, true); // one long unbroken spell
        }

        List<IncidentOccurrence> rows = occurrences.findSpellShapeRowsDescending(incidentId(hash), t0, 2);

        assertThat(rows.stream().map(row -> row.getId().getSampledAt()))
                .containsExactly(t0.plusSeconds(300), t0.plusSeconds(240));
    }

    /* ---------------- #365: the burst bins (ALARM-COST-MODEL §4.1a / §14.5) ---------------- */

    @Test
    void theBurstBinsSplitTheVerySameDeltasIntoInsideAndOutsideTheFloodWindow() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T09:00:00Z");
        Instant asOf = t0.plusSeconds(2_400); // 09:40 — W = 10 min ⇒ burst (09:30, 09:40]
        ingest(hash, t0, 5, false); // 09:00 birth +5  ─ outside both bins
        ingest(hash, t0.plusSeconds(600), 15, false); // 09:10 +10       ─ outside both bins
        ingest(hash, t0.plusSeconds(1_500), 18, false); // 09:25 +3        ─ PRIOR bin
        ingest(hash, t0.plusSeconds(2_100), 28, false); // 09:35 +10       ─ BURST bin
        ingest(hash, asOf, 40, false); // 09:40 +12       ─ BURST bin

        Aggregate row = aggregate(hash, t0, asOf);

        // The base could only ever answer this one number — 40 arrivals over 40 minutes, with no
        // way to say that 22 of them landed in the last ten (probe on the shipped SQL: [1,40,5,5]).
        assertThat(row.arrivals()).isEqualTo(40);
        assertThat(row.burstArrivals()).isEqualTo(22);
        assertThat(row.priorBurstArrivals()).isEqualTo(3);
        assertThat(row.burstObservedSamples()).isEqualTo(2);
        assertThat(row.burstTrustedSamples()).isEqualTo(2);
        // §13 F3, in SQL: the bins are FILTERS over the same delta set, never a second sum. So
        // `outside` is a subtraction and every arrival is banked exactly once, at one weight.
        assertThat(row.arrivals() - row.burstArrivals()).isEqualTo(18);
    }

    @Test
    void theBinsAreHalfOpenSoASampleExactlyOnAnEdgeBelongsToTheOLDERBin() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T11:00:00Z");
        Instant asOf = t0.plusSeconds(2_400); // 11:40 ⇒ burst (11:30, 11:40], prior (11:20, 11:30]
        ingest(hash, t0, 5, false); // 11:00 birth +5
        ingest(hash, t0.plusSeconds(1_200), 15, false); // 11:20 +10 ─ ON the prior bin's own floor
        ingest(hash, t0.plusSeconds(1_800), 22, false); // 11:30 +7  ─ ON the burst bin's floor
        ingest(hash, asOf, 23, false); // 11:40 +1  ─ ON the burst bin's ceiling

        Aggregate row = aggregate(hash, t0, asOf);

        // (from, to] throughout: an edge sample falls OUT of the newer bin and INTO the older one,
        // so no delta can ever be claimed by two bins at once (the partition, at its seams).
        assertThat(row.arrivals()).isEqualTo(23);
        assertThat(row.burstArrivals()).isEqualTo(1);
        assertThat(row.priorBurstArrivals()).isEqualTo(7);
    }

    @Test
    void aBirthInsideTheFloodWindowCountsItsWholePopulationOnceInBOTHArrivalsAndTheBurstBin() {
        String hash = "it-" + UUID.randomUUID();
        Instant windowStart = Instant.parse("2026-08-10T13:00:00Z");
        Instant asOf = windowStart.plusSeconds(2_400); // 13:40 ⇒ burst (13:30, 13:40]
        // The F3 birth delta, now inside W: `0 -> 5000` in ONE bucket is the largest flood there
        // is, and §4.1a weighs it ONCE at gamma rather than re-banking it with a second factor.
        ingest(hash, windowStart.plusSeconds(2_100), 5_000, false); // 13:35 birth
        ingest(hash, asOf, 5_000, false); // 13:40 flat

        Aggregate row = aggregate(hash, windowStart, asOf);

        assertThat(row.arrivals()).isEqualTo(5_000);
        assertThat(row.burstArrivals()).isEqualTo(5_000); // the SAME delta, seen through the bin
        assertThat(row.priorBurstArrivals()).isZero();
        assertThat(row.arrivals() - row.burstArrivals()).isZero(); // outside_W = 0 ⇒ F = log2(1+8*5000)
    }

    @Test
    void aWhollyTruncatedBurstBinReportsObservedButZeroTrustedSamplesSoTheGateMustRefuseToFire() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T15:00:00Z");
        Instant asOf = t0.plusSeconds(2_400); // 15:40 ⇒ burst (15:30, 15:40]
        ingest(hash, t0, 10, false); // birth +10, trusted
        ingest(hash, t0.plusSeconds(600), 20, false); // +10, trusted
        ingest(hash, t0.plusSeconds(2_100), 500, true); // 15:35 scan cap: a FLOOR, not a level
        ingest(hash, asOf, 500, true); // 15:40 still capped

        Aggregate row = aggregate(hash, t0, asOf);

        // The 28d window is only PARTIALLY untrusted, so `arrivals` keeps what it could measure...
        assertThat(row.arrivals()).isEqualTo(20);
        assertThat(row.trustedSamples()).isEqualTo(2);
        // ...but the BURST bin has samples and can trust none of them ⇒ burstUnknown ⇒ gate OFF.
        // A capped engine must not be readable as "not spiking" — and it must not fake a flood.
        assertThat(row.burstObservedSamples()).isEqualTo(2);
        assertThat(row.burstTrustedSamples()).isZero();
        assertThat(row.burstArrivals()).isZero();
    }

    @Test
    void aWhollyBlindBurstBinReportsTheSameUnknownShapeAsAWhollyTruncatedOne() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T17:00:00Z");
        Instant asOf = t0.plusSeconds(2_400);
        // #302's untrust reason, verbatim: an unreachable engine is missing members, so its
        // drop-and-recover is an outage edge, never a burst. Both reasons discard identically.
        ingest(hash, t0, 10, false, true); // birth +10
        ingest(hash, t0.plusSeconds(600), 20, false, true); // +10
        ingest(hash, t0.plusSeconds(2_100), 900, false, false); // 15:35 blind cycle
        ingest(hash, asOf, 900, false, false); // 15:40 blind cycle

        Aggregate row = aggregate(hash, t0, asOf);

        assertThat(row.arrivals()).isEqualTo(20);
        assertThat(row.burstObservedSamples()).isEqualTo(2);
        assertThat(row.burstTrustedSamples()).isZero();
        assertThat(row.burstArrivals()).isZero();
    }

    @Test
    void aWindowSTARTINGInsideTheFloodWindowStillDiscardsItsFirstRowInsteadOfBankingAFakeFlood() {
        String hash = "it-" + UUID.randomUUID();
        Instant birth = Instant.parse("2026-08-10T19:00:00Z");
        Instant asOf = birth.plusSeconds(2_400); // 19:40 ⇒ burst (19:30, 19:40]
        Instant windowStart = birth.plusSeconds(1_980); // 19:33 — mid-life, and INSIDE the burst bin
        // The nastiest shape the amendment could have introduced: if the birth seeding leaked to a
        // window that merely OPENS mid-life, a standing 5 000-member class would read as a 5 003
        // flood on every single rebuild, forever. The `first_seen` join keeps the 0 baseline on
        // the incident's OWN first row only, so the mid-life first row stays undifferenceable and
        // never reaches either bin.
        ingest(hash, birth, 5_000, false);
        ingest(hash, windowStart, 5_000, false);
        ingest(hash, birth.plusSeconds(2_220), 5_003, false); // 19:37 +3 — the only real growth
        ingest(hash, asOf, 5_003, false); // 19:40 flat

        Aggregate row = aggregate(hash, windowStart, asOf);

        assertThat(row.arrivals()).isEqualTo(3);
        assertThat(row.burstArrivals()).isEqualTo(3);
        assertThat(row.burstObservedSamples()).isEqualTo(2); // 19:37 and 19:40; 19:33 is undifferenceable
        assertThat(row.priorBurstArrivals()).isZero();
        // ...and the whole-life read still finds the birth, once, in both the window and the bin.
        assertThat(aggregate(hash, birth, asOf).arrivals()).isEqualTo(5_003);
    }

    /* ---------------- #372: observation SCOPE — a registry edit is not movement ---------------- */

    @Test
    void aReEnableEdgeAcrossAFLEETChangeIsNotBankedAsArrivals() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-11T09:00:00Z");
        // A settled single-engine class; at t0+120 a second engine is RE-ENABLED in the registry
        // and brings its own 900 members into scope. Every row here is cycle_complete = true and
        // untruncated — the passes really were complete FOR THEIR SCOPE — so the two quality
        // markers see nothing at all and the base banks the level shift as +900 arrivals (probe on
        // the shipped SQL: arrivals=900, observed=3, trusted=3). Nobody's jobs failed.
        ingest(hash, t0.minusSeconds(60), 100, false, true, FLEET);
        ingest(hash, t0, 100, false, true, FLEET);
        ingest(hash, t0.plusSeconds(60), 100, false, true, FLEET);
        ingest(hash, t0.plusSeconds(120), 1000, false, true, FLEET_GROWN); // re-enable
        ingest(hash, t0.plusSeconds(180), 1000, false, true, FLEET_GROWN);

        assertThat(arrivals(hash, t0)).isZero();
        // ...and the discard is COUNTED, never silent: the window is PARTIAL, not unknown.
        assertThat(observedSamples(hash, t0)).isEqualTo(3);
        assertThat(trustedSamples(hash, t0)).isEqualTo(2);
    }

    @Test
    void aDisableEdgeIsNotNegativeBankedAndTheNewERABaselineReSeedsOnTheNextSameFleetPair() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-11T11:00:00Z");
        // The mirror operation. The drop edge was already clamped by SUM(GREATEST(delta,0)), but
        // the level shift silently survived as the new baseline; what must hold now is that growth
        // INSIDE the new era is still measured honestly the moment two same-fleet rows exist.
        ingest(hash, t0.minusSeconds(60), 1000, false, true, FLEET_GROWN);
        ingest(hash, t0, 1000, false, true, FLEET_GROWN);
        ingest(hash, t0.plusSeconds(60), 100, false, true, FLEET); // disable: 900 members leave scope
        ingest(hash, t0.plusSeconds(120), 100, false, true, FLEET);
        ingest(hash, t0.plusSeconds(180), 150, false, true, FLEET); // +50 REAL growth, one fleet

        assertThat(arrivals(hash, t0)).isEqualTo(50);
        // t0 itself has no in-window predecessor (and is not the birth), so 3 differenceable rows;
        // the disable edge at +60 is observed but not trusted, and the new era measures normally.
        assertThat(observedSamples(hash, t0)).isEqualTo(3);
        assertThat(trustedSamples(hash, t0)).isEqualTo(2);
    }

    @Test
    void twoAdjacentUNRECORDEDRowsAreNotTrustedBecauseTheyAreNotKNOWNToShareAScope() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-11T13:00:00Z");
        // Exactly the shape the V22 fail-closed backfill leaves behind. The first draft of the
        // predicate would have let '' compare equal to '' and banked this +20; §16.5's rule is
        // "comparable to nothing, ITSELF INCLUDED" precisely so a pre-V22 stretch stays inert.
        ingest(hash, t0, 10, false, true, FLEET); // birth, scope recorded: a real +10
        ingest(hash, t0.plusSeconds(60), 10, false, true, FLEET_UNRECORDED);
        ingest(hash, t0.plusSeconds(120), 30, false, true, FLEET_UNRECORDED); // +20 across ''/''

        // The base banks all 30. Only the birth survives: neither the FLEET->'' edge nor the
        // ''->'' one is difference-comparable, so the +20 is discarded rather than banked.
        assertThat(arrivals(hash, t0)).isEqualTo(10);
        assertThat(observedSamples(hash, t0)).isEqualTo(3);
        assertThat(trustedSamples(hash, t0)).isEqualTo(1); // the birth alone
    }

    @Test
    void aBirthRowWithARecordedFleetStillCountsItsWholePopulationOnce() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-11T15:00:00Z");
        // FIX 3 survives the new terms: LAG(fleet) is NULL on the incident's OWN first row, and
        // COALESCE(..., o.fleet) self-compares there — a birth differences against the seeded 0,
        // not against another fleet's level.
        ingest(hash, t0, 5_000, false, true, FLEET);
        ingest(hash, t0.plusSeconds(60), 5_000, false, true, FLEET);

        assertThat(arrivals(hash, t0)).isEqualTo(5_000);
        assertThat(trustedSamples(hash, t0)).isEqualTo(2);
    }

    @Test
    void aBirthRowThatNeverRecordedItsOWNScopeIsNotAnArrival() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-11T17:00:00Z");
        // The other half of the birth rule: the self-compare must not smuggle an UNRECORDED row
        // through. A birth still has to state the scope it was born into (`fleet <> ''`).
        ingest(hash, t0, 5_000, false, true, FLEET_UNRECORDED);
        ingest(hash, t0.plusSeconds(60), 5_000, false, true, FLEET_UNRECORDED);

        assertThat(arrivals(hash, t0)).isZero();
        assertThat(observedSamples(hash, t0)).isEqualTo(2);
        assertThat(trustedSamples(hash, t0)).isZero();
    }

    @Test
    void theBurstBinsInheritTheScopeDisciplineSoAReEnableIsNeverReadAsAFlood() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-11T19:00:00Z");
        Instant asOf = t0.plusSeconds(2_400); // 19:40 ⇒ burst (19:30, 19:40]
        // The #365 bins FILTER the same d.trusted, so they inherit the terms for free — worth
        // pinning, because a re-enable read as a flood is the loudest possible false alarm: it
        // promotes the class to the top of the attention order on the strength of an admin action.
        ingest(hash, t0, 10, false, true, FLEET); // 19:00 birth +10
        ingest(hash, t0.plusSeconds(600), 20, false, true, FLEET); // 19:10 +10
        ingest(hash, t0.plusSeconds(2_100), 900, false, true, FLEET_GROWN); // 19:35 re-enable
        ingest(hash, asOf, 900, false, true, FLEET_GROWN); // 19:40 flat

        Aggregate row = aggregate(hash, t0, asOf);

        assertThat(row.arrivals()).isEqualTo(20); // the two real ones, kept
        assertThat(row.burstArrivals()).isZero(); // NOT the 880 the base bins
        assertThat(row.burstObservedSamples()).isEqualTo(2);
        assertThat(row.burstTrustedSamples()).isEqualTo(1); // only the 19:40 same-fleet pair
    }

    @Test
    void theSpellShapeQueryProjectsTheRowsRecordedFLEET() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-11T21:00:00Z");
        // C4's substrate: scope is a property of the ROW and must round-trip onto the entity
        // (V22 mapping + ddl-auto=validate) exactly like the two quality markers beside it.
        ingestJobs(hash, t0, 5, 5, 0, true, FLEET);
        ingestJobs(hash, t0.plusSeconds(60), 7, 5, 2, true, FLEET); // spell starts
        ingestJobs(hash, t0.plusSeconds(120), 5, 5, 0, true, FLEET_GROWN); // closing zero, NEW fleet
        ingestJobs(hash, t0.plusSeconds(180), 5, 5, 0, true, FLEET_GROWN); // +1 look-ahead

        List<IncidentOccurrence> rows = occurrences.findSpellShapeRowsDescending(incidentId(hash), t0, 100);

        assertThat(rows.stream().map(IncidentOccurrence::getFleet))
                .containsExactly("engine-7,engine-a", "engine-7,engine-a", "engine-a");
    }

    /* ---------------- #372 item 7: the `until` cursor past the 30-day clamp ---------------- */

    @Test
    void theUntilCursorReachesRowsOlderThanASingleClampedWindowAndTheSeamNeverRepeatsARow() {
        String hash = "it-" + UUID.randomUUID();
        Instant asOf = Instant.parse("2026-08-12T00:00:00Z");
        Instant windowFloor = asOf.minus(Duration.ofDays(30)); // what ONE clamped call reaches back to
        Instant old = asOf.minus(Duration.ofDays(45)); // 45 d back: structurally unreachable in one call
        Instant recent = asOf.minus(Duration.ofDays(10));
        // G5 needs >= 56 d of same-fleet trusted span and IncidentQueryService clamps a single call
        // to MAX_WINDOW_HOURS = 30 d with no cursor — so before this the era boundary the amended
        // G5 measures FROM could never be seen over REST at all (§16.7). Same fleet throughout, so
        // this fixture is purely about REACHABILITY, not about the scope predicate.
        ingest(hash, old, 10, false);
        ingest(hash, windowFloor, 20, false); // exactly ON the seam
        ingest(hash, recent, 30, false);
        long id = incidentId(hash);

        List<Instant> single =
                occurrences
                        .findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(id, windowFloor)
                        .stream()
                        .map(row -> row.getId().getSampledAt())
                        .toList();
        List<Instant> previousPage = occurrences
                .findByIdIncidentIdAndIdSampledAtGreaterThanEqualAndIdSampledAtLessThanOrderByIdSampledAtAsc(
                        id, windowFloor.minus(Duration.ofDays(30)), windowFloor)
                .stream()
                .map(row -> row.getId().getSampledAt())
                .toList();

        // One call still returns exactly the most recent clamped slice — no behavior change...
        assertThat(single).containsExactly(windowFloor, recent);
        // ...and the previous PAGE, still bounded to one window, reaches what it could not.
        assertThat(previousPage).containsExactly(old);
        // Half-open [since, until): the seam row belongs to the NEWER page and to only one page.
        assertThat(previousPage).doesNotContain(windowFloor);
    }

    /* ---------------- helpers ---------------- */

    /**
     * One incident's row from the aggregate, with the {@code burst_arrivals <= arrivals}
     * containment invariant (§14.5) asserted on EVERY fixture in this class — the burst bin is a
     * FILTER over the same deltas, so a violation would mean the partition (and with it §13 F3's
     * no-double-banking guarantee) had been broken by a later edit.
     */
    private record Aggregate(
            long arrivals,
            long observedSamples,
            long trustedSamples,
            long burstArrivals,
            long priorBurstArrivals,
            long burstObservedSamples,
            long burstTrustedSamples) {

        private static final Aggregate ABSENT = new Aggregate(0, 0, 0, 0, 0, 0, 0);
    }

    /** Burst bins anchored on {@code asOf}: current {@code (asOf−W, asOf]}, prior one W before. */
    private Aggregate aggregate(String hash, Instant since, Instant asOf) {
        return aggregate(hash, since, asOf.minus(BURST_WINDOW), asOf.minus(BURST_WINDOW.multipliedBy(2)));
    }

    private Aggregate aggregate(String hash, Instant since, Instant burstSince, Instant priorBurstSince) {
        long incidentId = incidentId(hash);
        Aggregate found = Aggregate.ABSENT;
        for (Object[] row : occurrences.arrivalsSince(since, burstSince, priorBurstSince)) {
            Aggregate parsed = new Aggregate(
                    number(row[1]),
                    number(row[2]),
                    number(row[3]),
                    number(row[4]),
                    number(row[5]),
                    number(row[6]),
                    number(row[7]));
            assertThat(parsed.burstArrivals())
                    .as("containment invariant burst_arrivals <= arrivals, incident %s", row[0])
                    .isLessThanOrEqualTo(parsed.arrivals());
            if (number(row[0]) == incidentId) {
                found = parsed;
            }
        }
        return found;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private void ingest(String hash, Instant bucket, long total, boolean truncated) {
        ingest(hash, bucket, total, truncated, true);
    }

    private void ingest(String hash, Instant bucket, long total, boolean truncated, boolean cycleComplete) {
        ingest(hash, bucket, total, truncated, cycleComplete, FLEET);
    }

    /**
     * Every pre-#372 fixture above ingests under the SAME {@link #FLEET}, so the scope terms in
     * the trusted predicate are satisfied throughout and those fixtures' numbers are byte-identical
     * to the pre-#372 suite — the scope rule only bites on a composition CHANGE or an unrecorded
     * scope, both of which the #372 fixtures below stage explicitly.
     */
    private void ingest(
            String hash,
            Instant bucket,
            long total,
            boolean truncated,
            boolean cycleComplete,
            Set<String> fleetEngineIds) {
        AggregationSample sample = new AggregationSample(
                List.of(),
                List.of(group(hash, total)),
                bucket,
                truncated ? Set.of("engine-a") : Set.of(),
                cycleComplete,
                fleetEngineIds);
        ledger.ingest(sample, bucket);
    }

    private void ingestJobs(
            String hash, Instant bucket, long total, long deadLetters, long retrying, boolean cycleComplete) {
        ingestJobs(hash, bucket, total, deadLetters, retrying, cycleComplete, FLEET);
    }

    private void ingestJobs(
            String hash,
            Instant bucket,
            long total,
            long deadLetters,
            long retrying,
            boolean cycleComplete,
            Set<String> fleetEngineIds) {
        ErrorGroup group = new ErrorGroup(
                hash,
                1,
                "java.net.SocketTimeoutException",
                "timeout after # ms",
                "timeout after 5000 ms",
                total,
                deadLetters,
                retrying,
                Map.of("engine-a", Map.of("order:v3", total)));
        ledger.ingest(
                new AggregationSample(List.of(), List.of(group), bucket, Set.of(), cycleComplete, fleetEngineIds),
                bucket);
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
        return noBurstBins(hash, since).arrivals();
    }

    /** How many differenceable samples the window held (a class's own birth row counts as one). */
    private long observedSamples(String hash, Instant since) {
        return noBurstBins(hash, since).observedSamples();
    }

    /** How many of those had BOTH endpoints fully observed — 0 means the window is unknown. */
    private long trustedSamples(String hash, Instant since) {
        return noBurstBins(hash, since).trustedSamples();
    }

    /**
     * The pre-#365 assertions, unchanged: bins anchored past the end of every fixture, so both are
     * EMPTY and the four original columns must read exactly as they did before the amendment.
     */
    private Aggregate noBurstBins(String hash, Instant since) {
        return aggregate(hash, since, FAR_FUTURE, FAR_FUTURE);
    }

    private List<Long> closedDurations(long incidentId) {
        return episodes.closedEpisodeDurationSeconds().stream()
                .filter(row -> ((Number) row[0]).longValue() == incidentId)
                .map(row -> ((Number) row[1]).longValue())
                .toList();
    }
}
