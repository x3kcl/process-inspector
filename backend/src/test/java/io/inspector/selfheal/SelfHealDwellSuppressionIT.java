package io.inspector.selfheal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentRepository;
import io.inspector.snapshot.SnapshotSampler;
import io.inspector.support.EngineSeed;
import io.inspector.support.SelfHealSeed;
import io.inspector.support.SelfHealSeed.Fixture;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Issue #359 — the MOST VALUABLE of the three new arcs (per the issue's own framing): the
 * server-side minimum-dwell rule (RETRYING-RISK-LANE.md §4.2 rule 3, the DMKD temporal-stability
 * requirement the design treats as normative) actually SUPPRESSING a lane flip across REAL
 * sampler cycles, against REAL engine/BFF state. Nothing exercised this against anything but
 * synthetic fixtures before this fixture existed ({@code DwellStateMachineTest} proves the pure
 * state machine; this proves the WIRING — {@code SnapshotSampler#sampleOnce} →
 * {@code AggregationSampledEvent} → {@code SelfHealStatsService#onAggregationSampled} →
 * {@code DwellStateMachine#advance} — actually holds a stale lane across cycles it should, and
 * commits the new one exactly on the cycle it should).
 *
 * <p>{@code inspector.selfheal.floor=2} (two spells is enough to leave {@code
 * INSUFFICIENT_HISTORY} — the LANE this test cares about is MIXED vs INSUFFICIENT_HISTORY, not
 * LIKELY, so the small floor is not a shortcut around the interesting behavior) and {@code
 * dwell-cycles=3} (small enough to prove suppression in a handful of extra {@code
 * sampleOnce()} calls, large enough that "holds across MULTIPLE distinct cycles" is a real
 * claim, not a one-cycle coincidence).
 *
 * <p>The arc: one baseline + two self-healed spells make the RAW/candidate lane cross into
 * MIXED (n=2, healed=2 → Wilson LB≈0.342/UB≈0.9999 — inside neither the LIKELY nor UNLIKELY
 * band, §4.1). The DISPLAYED lane (what {@code GET /api/incidents/{id}} actually serves) must
 * stay {@code INSUFFICIENT_HISTORY} for {@code dwell-cycles − 1} further COMPLETE cycles with
 * NO new engine activity at all (the underlying {@code n}/{@code healed}/Wilson bounds are
 * already visible on the same response — {@code SelfHealStats}' javadoc: {@code n}/{@code
 * healed} are always present, {@code wilsonLow}/{@code wilsonHigh} once above the floor — this
 * is the precise, observable difference between the RAW statistic and the dwell-protected
 * DISPLAYED lane) and commit to MIXED only on the {@code dwell-cycles}-th identical cycle.
 *
 * <p>LOCAL-ONLY (failsafe *IT, it-actions family — not in ci.yml's itClass).
 *
 * <p>Requires: docker compose -f docker/docker-compose.dev.yml up -d (flowable-6 profile)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ENGINE_A_PASSWORD=test",
            "inspector.snapshot.sample-interval=PT1H",
            // See SelfHealMixedLaneIT's identical override for why: occurrence rows upsert keyed
            // by wall-clock bucket, so rapid-fire sampleOnce() calls silently collapse (proven
            // live, 2026-08-04) unless SelfHealSeed#awaitNextBucket can rely on a short bucket.
            "inspector.snapshot.bucket-width=PT15S", // keep in lockstep with BUCKET_WIDTH below
            "inspector.selfheal.floor=2",
            "inspector.selfheal.dwell-cycles=3" // keep in lockstep with DWELL_CYCLES below
        })
// it-selfheal (LATER = higher precedence) restates the it-actions engine list with a raised
// dlq-scan-cap — see that file's own doc comment for why a `properties=` override can't do this
// (Spring's list-valued binding doesn't merge across sources by index).
@ActiveProfiles({"it-actions", "it-selfheal"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfHealDwellSuppressionIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";
    private static final int DWELL_CYCLES = 3;
    private static final Duration BUCKET_WIDTH = Duration.ofSeconds(15); // keep in lockstep with the property above

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
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    SnapshotSampler sampler;

    @Autowired
    IncidentRepository incidents;

    private RestClient engine;
    private Fixture fixture;

    @BeforeAll
    void deployTheRunUniqueFixture() throws Exception {
        engine = EngineSeed.requireReachable(ENGINE, "");
        fixture = SelfHealSeed.deploy(engine, "Dwell", "R3/PT1S");
    }

    @AfterAll
    void deleteTheRunUniqueDeployments() {
        SelfHealSeed.cleanupQuietly(engine, fixture);
    }

    @Test
    void theDisplayedLaneHoldsThroughDwellThenCommitsExactlyOnTheNthCycle() throws Exception {
        String baseline = SelfHealSeed.startBaseline(engine, fixture);
        SelfHealSeed.awaitDeadLettered(engine, baseline);
        sampler.sampleOnce(); // first sight: INSUFFICIENT_HISTORY, n=0

        long incidentId = ourIncident().getId();
        assertThat(selfHealJson(incidentId).path("lane").asText()).isEqualTo("INSUFFICIENT_HISTORY");

        /* ---- ONE throwaway warm-up spell, structurally left-censored — see
        SelfHealLikelyLaneIT's identical warm-up for why (the DB-side spell-shape query
        drops the quiet row before a brand-new incident's first-ever spell, landing it at
        array index 0). Absorbing it here is what makes spells 1/2 below start at a
        non-zero index and actually count toward n. ---- */
        selfHealOneSpell();

        /* ---- two self-healed spells cross the floor=2 — candidate becomes MIXED ---- */
        selfHealOneSpell(); // spell 1: start -> RETRYING -> sample -> heal -> completed -> sample
        String second = SelfHealSeed.startTransient(engine, fixture);
        SelfHealSeed.awaitRetrying(engine, second);
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce(); // spell 2 START — also spell 1's look-ahead (n still 1 here: spell 2 is live)
        SelfHealSeed.heal(engine, second);
        SelfHealSeed.awaitCompleted(engine, second);
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce(); // spell 2 END (retryingCount=0)

        /* ---- cycle A: spell 2's own look-ahead — n reaches 2, candidate flips to MIXED,
        dwell pending-cycles=1 (< dwell-cycles=3) — the DISPLAYED lane must NOT move yet,
        even though n/healed/wilsonLow on the SAME response already reflect the new data
        (the precise raw-vs-displayed distinction this test exists to prove). ---- */
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce();
        JsonNode afterCycleA = selfHealJson(incidentId);
        assertThat(afterCycleA.path("n").asInt()).isEqualTo(2);
        assertThat(afterCycleA.path("healed").asInt()).isEqualTo(2);
        assertThat(afterCycleA.path("wilsonLow").asDouble())
                .as("the RAW statistic is already visible — n>=floor")
                .isBetween(0.0, 0.70);
        assertThat(afterCycleA.path("lane").asText())
                .as("dwell pending-cycles=1 of %d — the DISPLAYED lane must still hold the old value", DWELL_CYCLES)
                .isEqualTo("INSUFFICIENT_HISTORY");

        /* ---- cycles B..(N-1): SAME data, no new engine activity — still held every time. Real
        distinct sampler cycles (not just repeated calls): each still lands in its own
        fresh bucket, even though the underlying occurrence VALUES never change. ---- */
        for (int cycle = 2; cycle < DWELL_CYCLES; cycle++) {
            SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
            sampler.sampleOnce();
            assertThat(selfHealJson(incidentId).path("lane").asText())
                    .as(
                            "dwell pending-cycles=%d of %d — still held, proving suppression ACROSS cycles, not just"
                                    + " one",
                            cycle, DWELL_CYCLES)
                    .isEqualTo("INSUFFICIENT_HISTORY");
        }

        /* ---- cycle N: pending-cycles reaches dwell-cycles — commits ---- */
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce();
        assertThat(selfHealJson(incidentId).path("lane").asText())
                .as("RETRYING-RISK-LANE.md §4.2 rule 3: the Nth consecutive complete cycle commits the lane")
                .isEqualTo("SELF_HEAL_MIXED");
    }

    private void selfHealOneSpell() {
        String instance = SelfHealSeed.startTransient(engine, fixture);
        SelfHealSeed.awaitRetrying(engine, instance);
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce(); // START
        SelfHealSeed.heal(engine, instance);
        SelfHealSeed.awaitCompleted(engine, instance);
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce(); // END
    }

    /* ---------------- helpers ---------------- */

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private Incident ourIncident() {
        List<Incident> matching = incidents.findAll().stream()
                .filter(i -> i.getNormalizedMessage() != null
                        && i.getNormalizedMessage().contains(fixture.token()))
                .toList();
        assertThat(matching)
                .as("exactly one incident for token %s — a second row would mean hash instability", fixture.token())
                .hasSize(1);
        return matching.get(0);
    }

    private JsonNode selfHealJson(long incidentId) throws Exception {
        ResponseEntity<String> detail = as("viewer").getForEntity("/api/incidents/" + incidentId, String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(detail.getBody()).path("incident").path("selfHeal");
    }
}
