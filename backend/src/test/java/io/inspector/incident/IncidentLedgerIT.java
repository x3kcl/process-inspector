package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

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
                    // #372/V22: the pass's observation SCOPE, canonical (sorted, comma-joined).
                    assertThat(o.getFleet()).isEqualTo(CANONICAL_FLEET);
                });
    }

    @Test
    void theFleetIsRecordedOnBlindCyclesToo() {
        // Scope is the INTENT set and is ALWAYS known — even when quality is not. A blind pass
        // still knows which engines it fanned out to, so its row states its scope; dropping the
        // marker on blind rows would make an outage indistinguishable from a pre-V22 row and would
        // permanently sever the series at every blip.
        String hash = "it-" + UUID.randomUUID();
        Instant blindBucket = Instant.parse("2026-07-19T09:00:00Z");
        Instant completeBucket = Instant.parse("2026-07-19T09:01:00Z");

        ledger.ingest(sample(blindBucket, false, group(hash, 7, 5, 2)), blindBucket);
        ledger.ingest(sample(completeBucket, true, group(hash, 8, 6, 2)), completeBucket);

        long id = incidents
                .findBySignatureHashAndAlgoVersion(hash, 1)
                .orElseThrow()
                .getId();
        assertThat(occurrences.findByIdIncidentIdOrderByIdSampledAtAsc(id))
                .extracting(IncidentOccurrence::isCycleComplete, IncidentOccurrence::getFleet)
                .containsExactly(tuple(false, CANONICAL_FLEET), tuple(true, CANONICAL_FLEET));
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

    /**
     * The #302 defect, reproduced then proven fixed against REAL Postgres persistence: a cycle
     * where an owning engine was unreachable ({@code cycleComplete=false}) must never arm the
     * zero-state gate, even though the group is entirely absent from that cycle's sample (the
     * exact shape an unreachable-engine cycle produces — see {@link
     * io.inspector.snapshot.PollingSnapshotSource}). Only a genuinely COMPLETE cycle observing
     * the class absent may arm it; only then may the class's return regress the incident.
     */
    @Test
    void aBlindCycleNeverArmsTheGateSoTheFailuresReturnNeverRegresses() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-07-25T09:00:00Z");

        // 1. first sighting → OPEN
        ledger.ingest(sample(t0, group(hash, 6, 6, 0)), t0);
        Incident row = incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();

        // 2. a human resolves (mirrors the mid-cycle-resolve test's simulated verb effect)
        jdbc.update(
                "UPDATE incident_episode SET ended_at = now(), resolved_by = 'it-operator',"
                        + " resolve_reason = 'fixed by config rollout' WHERE incident_id = ? AND ended_at IS NULL",
                row.getId());
        jdbc.update(
                "UPDATE incident SET state = 'RESOLVED', seen_zero_since_resolve = false, version = version + 1"
                        + " WHERE id = ?",
                row.getId());

        // 3. a BLIND cycle: the owning engine was unreachable, so the group is absent from the
        //    sample for a reason that has NOTHING to do with resolution — the gate must stay shut.
        Instant t1 = t0.plusSeconds(60);
        ledger.ingest(sample(t1, false), t1);
        assertThat(incidents
                        .findBySignatureHashAndAlgoVersion(hash, 1)
                        .orElseThrow()
                        .isSeenZeroSinceResolve())
                .as("an unreachable-engine cycle must never be read as an observed zero (#302)")
                .isFalse();

        // 4. the failure returns on a live (complete) cycle — since the gate never armed, this
        //    must NOT regress; the observation still stays honest (totals refresh).
        Instant t2 = t0.plusSeconds(120);
        ledger.ingest(sample(t2, group(hash, 5, 5, 0)), t2);
        Incident stillResolved =
                incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(stillResolved.getState()).isEqualTo(IncidentState.RESOLVED);
        assertThat(stillResolved.getRegressionCount()).isZero();
        assertThat(stillResolved.getLastTotal()).isEqualTo(5);
        Integer noRegressionAudit = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'incident-regressed' AND payload::text LIKE ?",
                Integer.class,
                "%" + hash + "%");
        assertThat(noRegressionAudit)
                .as("no bogus incident-regressed audit row")
                .isZero();

        // 5. a genuinely COMPLETE cycle that observes the class absent DOES arm the gate...
        Instant t3 = t0.plusSeconds(180);
        ledger.ingest(sample(t3), t3);
        assertThat(incidents
                        .findBySignatureHashAndAlgoVersion(hash, 1)
                        .orElseThrow()
                        .isSeenZeroSinceResolve())
                .isTrue();

        // 6. ...and only THEN does the failure's return correctly regress it.
        Instant t4 = t0.plusSeconds(240);
        ledger.ingest(sample(t4, group(hash, 4, 4, 0)), t4);
        Incident regressed =
                incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(regressed.getState()).isEqualTo(IncidentState.REGRESSED);
        assertThat(regressed.getRegressionCount()).isEqualTo(1);
        Integer regressionAudit = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'incident-regressed' AND payload::text LIKE ?",
                Integer.class,
                "%" + hash + "%");
        assertThat(regressionAudit).isEqualTo(1);
    }

    /**
     * The #380 defect, reproduced then proven fixed against REAL Postgres persistence: TWO
     * ROUTINE REGISTRY EDITS must not mint a false {@code REGRESSED}.
     *
     * <p>The class's members live ONLY on {@code engine-7}. An admin DISABLES that engine, so it
     * leaves {@code registry.all()} entirely: nobody fails to answer, {@code cycleComplete} stays
     * honestly TRUE for the now-smaller scope, and the class is "absent" — from a fleet that never
     * hosted it. Arming the zero-state gate there records an absence that was never OBSERVED; the
     * admin then re-enables and one untouched member is enough (`regression-min-count` floors at
     * 1) to transition RESOLVED → REGRESSED with a fresh episode and a fail-closed config-event
     * audit row, for a failure that never went away and never came back.
     *
     * <p>The tail of the arc proves the fix is a DELAY, not a mute: once the class has been
     * re-observed under the new fleet, a genuine drain on that stable fleet arms exactly as
     * before and the return regresses normally.
     */
    @Test
    void twoRegistryEditsNeverMintAFalseRegression() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-05T09:00:00Z");

        // 1. the class is OBSERVED under the full fleet, hosted only on engine-7 → OPEN
        ledger.ingest(sample(t0, FLEET_WITH_ENGINE_7, groupOn("engine-7", hash, 4)), t0);
        Incident row = incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(row.getState()).isEqualTo(IncidentState.OPEN);

        // 2. a human resolves (the simulated verb effect used by the other arcs in this class)
        resolveAtTheStore(row.getId());

        // 3. engine-7 is DISABLED. The cycle is COMPLETE — every engine it fanned out to answered
        //    — and the class is absent. It was never looked for.
        Instant t1 = t0.plusSeconds(60);
        ledger.ingest(sample(t1, FLEET), t1);
        assertThat(incidents
                        .findBySignatureHashAndAlgoVersion(hash, 1)
                        .orElseThrow()
                        .isSeenZeroSinceResolve())
                .as("a class whose only host just left the fleet was NOT observed absent (#380)")
                .isFalse();

        // 4. engine-7 is RE-ENABLED and the same untouched member reappears. The gate never
        //    armed, so this is a plain observation: no transition, no episode, no audit row.
        Instant t2 = t0.plusSeconds(120);
        ledger.ingest(sample(t2, FLEET_WITH_ENGINE_7, groupOn("engine-7", hash, 4)), t2);
        Incident afterReEnable =
                incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(afterReEnable.getState()).isEqualTo(IncidentState.RESOLVED);
        assertThat(afterReEnable.getRegressionCount()).isZero();
        assertThat(afterReEnable.getLastTotal())
                .as("the observation itself stays honest")
                .isEqualTo(4);
        assertThat(episodes.findByIncidentIdOrderByStartedAtDesc(afterReEnable.getId()))
                .as("no fabricated episode")
                .hasSize(1);
        assertThat(regressionAuditRows(hash))
                .as("no fabricated incident-regressed audit row")
                .isZero();

        // 5. the fix DELAYS, it does not mute: the class has now been re-observed under the new
        //    fleet, so a genuine drain on that stable fleet arms the gate exactly as before...
        Instant t3 = t0.plusSeconds(180);
        ledger.ingest(sample(t3, FLEET_WITH_ENGINE_7), t3);
        assertThat(incidents
                        .findBySignatureHashAndAlgoVersion(hash, 1)
                        .orElseThrow()
                        .isSeenZeroSinceResolve())
                .isTrue();

        // 6. ...and the return regresses normally, with its one audit row.
        Instant t4 = t0.plusSeconds(240);
        ledger.ingest(sample(t4, FLEET_WITH_ENGINE_7, groupOn("engine-7", hash, 4)), t4);
        Incident regressed =
                incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(regressed.getState()).isEqualTo(IncidentState.REGRESSED);
        assertThat(regressed.getRegressionCount()).isEqualTo(1);
        assertThat(regressionAuditRows(hash)).isEqualTo(1);
    }

    /**
     * #380's adjacent case against real persistence: with ZERO enabled engines the pass observed
     * nothing at all, yet the completeness loop over an empty {@code perEngine} left the flag
     * vacuously true — arming EVERY resolved incident at once. A pass with no observation scope
     * can vouch for no class's absence.
     */
    @Test
    void aFleetEmptyCycleArmsNoResolvedIncidentsGate() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-05T11:00:00Z");

        ledger.ingest(sample(t0, group(hash, 6, 6, 0)), t0);
        Incident row = incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        resolveAtTheStore(row.getId());

        Instant t1 = t0.plusSeconds(60);
        ledger.ingest(new AggregationSample(List.of(), List.of(), t1, Set.of(), true, Set.of()), t1);

        assertThat(incidents
                        .findBySignatureHashAndAlgoVersion(hash, 1)
                        .orElseThrow()
                        .isSeenZeroSinceResolve())
                .as("no enabled engines ⇒ nothing was observed ⇒ nothing may be armed (#380)")
                .isFalse();
    }

    /**
     * The must-not-change proof: a genuine drain and return on a CONSTANT fleet regresses exactly
     * as it always has — same transition, same episode count, same single audit row. #380's gate
     * only ever bites on a composition CHANGE or an unrecorded scope.
     */
    @Test
    void aGenuineDrainAndReturnOnAStableFleetStillRegresses() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-05T13:00:00Z");

        ledger.ingest(sample(t0, FLEET_WITH_ENGINE_7, groupOn("engine-7", hash, 6)), t0);
        Incident row = incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        resolveAtTheStore(row.getId());

        // the class genuinely drains — same fleet, every host still watched
        Instant t1 = t0.plusSeconds(60);
        ledger.ingest(sample(t1, FLEET_WITH_ENGINE_7), t1);
        assertThat(incidents
                        .findBySignatureHashAndAlgoVersion(hash, 1)
                        .orElseThrow()
                        .isSeenZeroSinceResolve())
                .isTrue();

        // ...and it comes back
        Instant t2 = t0.plusSeconds(120);
        ledger.ingest(sample(t2, FLEET_WITH_ENGINE_7, groupOn("engine-7", hash, 2)), t2);
        Incident regressed =
                incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(regressed.getState()).isEqualTo(IncidentState.REGRESSED);
        assertThat(regressed.getRegressionCount()).isEqualTo(1);
        assertThat(regressed.getLastRegressedAt()).isEqualTo(t2);
        assertThat(regressed.isSeenZeroSinceResolve()).isFalse();
        assertThat(episodes.findByIncidentIdOrderByStartedAtDesc(regressed.getId()))
                .hasSize(2);
        assertThat(regressionAuditRows(hash)).isEqualTo(1);
    }

    /** The S3 resolve verb's store effect, simulated (the shape every arc in this class uses). */
    private void resolveAtTheStore(long incidentId) {
        jdbc.update(
                "UPDATE incident_episode SET ended_at = now(), resolved_by = 'it-operator',"
                        + " resolve_reason = 'fixed by config rollout' WHERE incident_id = ? AND ended_at IS NULL",
                incidentId);
        jdbc.update(
                "UPDATE incident SET state = 'RESOLVED', seen_zero_since_resolve = false, version = version + 1"
                        + " WHERE id = ?",
                incidentId);
    }

    private int regressionAuditRows(String hash) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'incident-regressed' AND payload::text LIKE ?",
                Integer.class,
                "%" + hash + "%");
        return count != null ? count : -1;
    }

    /**
     * Issue #307 true-up: proves the {@code regress()} audit-failure compensation
     * (INCIDENT-LEDGER §5) is REACHABLE and CORRECT against a REAL Postgres/Hibernate stack —
     * not the dead code the pre-#316 shared-transaction shape produced (see
     * {@link IncidentLedgerService}'s class javadoc for the derivation). {@code
     * IncidentLedgerServiceTest}'s mocked-repository tests can prove {@code regress()} CALLS the
     * compensation, but the poison-propagation mechanism the old bug relied on runs through a
     * REAL shared Hibernate session/JDBC connection — no hand-rolled {@code
     * PlatformTransactionManager} fake can honestly gate that either way, so this IT injects a
     * genuine audit-insert failure (a scoped trigger, narrowly targeted at this test's own
     * signature hash so it never touches any other test's writes) and asserts against real
     * committed rows: the state claim and the unaudited episode are undone, the triggering
     * totals — genuinely observed — survive as plain observation, and no audit row for the
     * failed regression exists. A final cycle proves the ledger is left in a clean, fully
     * recoverable state (no residual poison from the failed attempt).
     */
    @Test
    void auditInsertFailureCompensatesTheRegressionAgainstRealPostgres() {
        String hash = "it-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-07-26T09:00:00Z");

        // 1. first sighting → OPEN
        ledger.ingest(sample(t0, group(hash, 6, 6, 0)), t0);
        Incident row = incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();

        // 2. a human resolves (mirrors the other mid-cycle-resolve tests' simulated verb effect)
        jdbc.update(
                "UPDATE incident_episode SET ended_at = now(), resolved_by = 'it-operator',"
                        + " resolve_reason = 'fixed by config rollout' WHERE incident_id = ? AND ended_at IS NULL",
                row.getId());
        jdbc.update(
                "UPDATE incident SET state = 'RESOLVED', seen_zero_since_resolve = false, version = version + 1"
                        + " WHERE id = ?",
                row.getId());

        // 3. a genuinely complete cycle observes the class absent → the zero-state gate arms
        Instant t1 = t0.plusSeconds(60);
        ledger.ingest(sample(t1), t1);
        assertThat(incidents
                        .findBySignatureHashAndAlgoVersion(hash, 1)
                        .orElseThrow()
                        .isSeenZeroSinceResolve())
                .isTrue();

        // 4. inject a deterministic audit-insert failure scoped to THIS incident's regression row
        installAuditFailureTrigger(hash);
        try {
            // 5. the class returns → the gate is open → regress() attempts the transition, the
            //    audit write fails, the compensation runs — the cycle never throws
            Instant t2 = t0.plusSeconds(120);
            assertThatCode(() -> ledger.ingest(sample(t2, group(hash, 3, 3, 0)), t2))
                    .doesNotThrowAnyException();

            Incident afterFailedRegression =
                    incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
            assertThat(afterFailedRegression.getState())
                    .as("the REGRESSED transition is undone")
                    .isEqualTo(IncidentState.RESOLVED);
            assertThat(afterFailedRegression.getRegressionCount()).isZero();
            assertThat(afterFailedRegression.getLastRegressedAt()).isNull();
            assertThat(afterFailedRegression.isSeenZeroSinceResolve())
                    .as("the gate re-arms so a later successful cycle can still regress it")
                    .isTrue();
            assertThat(afterFailedRegression.getLastTotal())
                    .as("the triggering total is a plain observation, not part of the reverted transition")
                    .isEqualTo(3);

            List<IncidentEpisode> eps = episodes.findByIncidentIdOrderByStartedAtDesc(afterFailedRegression.getId());
            assertThat(eps)
                    .as("the REGRESSED episode the failed attempt opened was deleted by the compensation")
                    .hasSize(1);
            assertThat(eps.get(0).getEndedAt()).isNotNull();

            Integer auditRows = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_entry WHERE action = 'incident-regressed' AND payload::text LIKE ?",
                    Integer.class,
                    "%" + hash + "%");
            assertThat(auditRows).as("the failed insert never landed a row").isZero();
        } finally {
            uninstallAuditFailureTrigger();
        }

        // 6. no residual poison: the SAME gate-armed incident regresses cleanly once the audit
        //    store is healthy again
        Instant t3 = t0.plusSeconds(180);
        ledger.ingest(sample(t3, group(hash, 4, 4, 0)), t3);
        Incident regressed =
                incidents.findBySignatureHashAndAlgoVersion(hash, 1).orElseThrow();
        assertThat(regressed.getState()).isEqualTo(IncidentState.REGRESSED);
        assertThat(regressed.getRegressionCount()).isEqualTo(1);
        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'incident-regressed' AND payload::text LIKE ?",
                Integer.class,
                "%" + hash + "%");
        assertThat(auditRows).isEqualTo(1);
    }

    /**
     * A scoped {@code BEFORE INSERT} trigger on {@code audit_entry} (the same partitioned parent
     * {@code audit_entry_append_only} already triggers on in V1 — row-level triggers on a
     * partitioned table clone to every partition) that raises for exactly one incident's
     * regression audit row, so the injected failure can never leak into any other test's writes.
     */
    private void installAuditFailureTrigger(String hash) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_audit_insert_307() RETURNS trigger AS $$
                BEGIN
                    IF NEW.action = 'incident-regressed' AND NEW.payload->>'signatureHash' = '%s' THEN
                        RAISE EXCEPTION 'issue #307 IT: synthetic audit-insert failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(hash));
        jdbc.execute("""
                CREATE TRIGGER test_fail_audit_insert_307_trigger
                    BEFORE INSERT ON audit_entry
                    FOR EACH ROW EXECUTE FUNCTION test_fail_audit_insert_307()
                """);
    }

    private void uninstallAuditFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_audit_insert_307_trigger ON audit_entry");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_audit_insert_307()");
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

    /** The steady observation SCOPE (#372, V22) the fleet-marker assertions expect on the row. */
    private static final Set<String> FLEET = Set.of("engine-b", "engine-a");

    /** Its canonical persisted form: sorted lexicographically, comma-joined. */
    private static final String CANONICAL_FLEET = "engine-a,engine-b";

    /** {@link #FLEET} plus the engine the #380 arcs disable and re-enable. */
    private static final Set<String> FLEET_WITH_ENGINE_7 = Set.of("engine-b", "engine-a", "engine-7");

    private static AggregationSample sample(Instant sampledAt, ErrorGroup... groups) {
        return new AggregationSample(List.of(), List.of(groups), sampledAt, Set.of(), true, FLEET);
    }

    /** #380: a COMPLETE cycle whose observation SCOPE is stated explicitly (a registry edit). */
    private static AggregationSample sample(Instant sampledAt, Set<String> fleetEngineIds, ErrorGroup... groups) {
        return new AggregationSample(List.of(), List.of(groups), sampledAt, Set.of(), true, fleetEngineIds);
    }

    /** #302: a cycle whose completeness is explicit — {@code cycleComplete=false} simulates an
     * unreachable owning engine (the group is simply absent, same shape as a real one). */
    private static AggregationSample sample(Instant sampledAt, boolean cycleComplete, ErrorGroup... groups) {
        return new AggregationSample(List.of(), List.of(groups), sampledAt, Set.of(), cycleComplete, FLEET);
    }

    /** #380: a class whose members live on ONE named engine — the host that gets disabled. */
    private static ErrorGroup groupOn(String engineId, String hash, long total) {
        return new ErrorGroup(
                hash,
                1,
                "java.net.SocketTimeoutException",
                "timeout after # ms",
                "timeout after 5000 ms",
                total,
                total,
                0,
                Map.of(engineId, Map.of("order:v3", total)));
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
