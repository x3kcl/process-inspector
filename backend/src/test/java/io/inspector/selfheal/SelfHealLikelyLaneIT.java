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
 * Issue #359 / RETRYING-RISK-LANE.md §7.2 panel finding G12 (blocker): before this fixture,
 * NOTHING on any deployment — dev, CI, or the demo — could ever transition a class's displayed
 * lane into {@code SELF_HEAL_LIKELY}, because every existing seed process (error-zoo, ACME)
 * fails permanently by construction (measured 2026-08-04,
 * docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md: zero self-heals ever observed on the pilot).
 * This is the first end-to-end proof that the lane is reachable at all.
 *
 * <p>Drives TEN genuinely unconfounded SELF-HEALED retrying spells for one run-unique error
 * class (§7.1's own floor — n=10 gives Wilson LB=0.722, clearly above the 0.70 LIKELY
 * threshold; n=9 sits exactly ON it, 0.701, too close to float precision to trust in a test)
 * through the REAL sampler → ledger → self-heal-stats pipeline: {@code demoSelfHealingBaseline}
 * establishes a standing dead-letter so the class stays "live" between spells (otherwise the
 * ledger would stop writing occurrence rows the instant nothing is currently failing, and
 * consecutive spells would merge into one — see that fixture's own doc comment), then each of
 * ten {@code demoSelfHealing} instances is started, observed actively RETRYING, healed directly
 * over REST (bypassing the BFF — no audit row, so it can never be confound-flagged), and
 * observed leaving the runtime table via successful completion (never a dead-letter).
 *
 * <p>{@code inspector.selfheal.dwell-cycles=1} — this test proves LIKELY is REACHABLE, not the
 * dwell/hysteresis mechanism itself (that is {@code SelfHealDwellSuppressionIT}), so the
 * displayed lane commits on the very first complete cycle that computes it. {@code floor} is
 * left at the PRODUCTION DEFAULT (10, unset) deliberately — this proves the shipped threshold
 * is reachable, not a lowered test-only one.
 *
 * <p>Real engine timing, not a fixed sleep: each spell's heal-to-completion leg is dominated by
 * the async executor's acquire-poll lag (proven live, 2026-08-04: ~15-45s per leg on this
 * harness, independent of the nominal retry-cycle interval), so this arc runs several minutes
 * end to end. LOCAL-ONLY (failsafe *IT, it-actions family — not in ci.yml's itClass, same as
 * {@code IncidentLedgerArcIT}).
 *
 * <p>Requires: docker compose -f docker/docker-compose.dev.yml up -d (flowable-6 profile)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ENGINE_A_PASSWORD=test",
            "inspector.snapshot.sample-interval=PT1H", // scheduler idle; cycles are driven by hand
            // See SelfHealMixedLaneIT's identical override for why: occurrence rows upsert keyed
            // by wall-clock bucket, so rapid-fire sampleOnce() calls silently collapse (proven
            // live, 2026-08-04) unless SelfHealSeed#awaitNextBucket can rely on a short bucket.
            "inspector.snapshot.bucket-width=PT15S", // keep in lockstep with BUCKET_WIDTH below
            "inspector.selfheal.dwell-cycles=1"
        })
// it-selfheal (LATER = higher precedence) restates the it-actions engine list with a raised
// dlq-scan-cap — see that file's own doc comment for why a `properties=` override can't do this
// (Spring's list-valued binding doesn't merge across sources by index).
@ActiveProfiles({"it-actions", "it-selfheal"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfHealLikelyLaneIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";
    private static final int SPELLS = 10; // RETRYING-RISK-LANE.md §7.1 floor — perfect record LB=0.722
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
        fixture = SelfHealSeed.deploy(engine, "Likely");
    }

    @AfterAll
    void deleteTheRunUniqueDeployments() {
        SelfHealSeed.cleanupQuietly(engine, fixture);
    }

    @Test
    void tenUnconfoundedSelfHealsCommitTheLikelyLane() throws Exception {
        /* ---- 0. standing baseline so the class never goes fully absent between spells ---- */
        String baseline = SelfHealSeed.startBaseline(engine, fixture);
        SelfHealSeed.awaitDeadLettered(engine, baseline);
        sampler.sampleOnce(); // first sight: OPEN incident + occurrence row (retryingCount=0, dlq=1)

        long incidentId = ourIncident().getId();
        assertThat(selfHealJson(incidentId).path("lane").asText()).isEqualTo("INSUFFICIENT_HISTORY");

        /* ---- 0..10: ONE throwaway warm-up spell, then the 10 spells the assertions read.
        IncidentOccurrenceRepository#findSpellShapeRowsDescending drops "quiet" rows
        (neither itself, its predecessor, nor its predecessor's predecessor retrying) —
        proven live, 2026-08-04: for a BRAND NEW incident the only quiet row available is
        the one immediately before its first-ever spell, which the LAG filter always drops,
        so that first spell's start lands at array index 0 and RetrySpellExtractor marks it
        left-censored (excluded, by design — see that repository method's own doc comment
        on the identical index-0 case for a row-CAPPED series). Real, pre-existing behavior,
        not something this test works around incorrectly: the warm-up spell absorbs it,
        and every spell after it starts at a non-zero array index. ---- */
        for (int i = 0; i < SPELLS + 1; i++) {
            String instance = SelfHealSeed.startHealable(engine, fixture);
            SelfHealSeed.awaitRetrying(engine, instance);
            SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
            sampler.sampleOnce(); // spell i START (retryingCount>0) — also spell (i-1)'s look-ahead
            SelfHealSeed.heal(engine, instance);
            SelfHealSeed.awaitCompleted(engine, instance); // proves it SUCCEEDED, never dead-lettered
            SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
            sampler.sampleOnce(); // spell i END (retryingCount=0, dlq unchanged)
        }
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce(); // the final spell's own look-ahead (§3.1: outcome judged spell+1 bucket)

        /* ---- 2. the class's own signature now carries 10 unconfounded SELF_HEALED spells
        (+1 excluded: the left-censored warm-up spell) ---- */
        JsonNode selfHeal = selfHealJson(incidentId);
        assertThat(selfHeal.path("n").asInt()).isEqualTo(SPELLS);
        assertThat(selfHeal.path("healed").asInt()).isEqualTo(SPELLS);
        assertThat(selfHeal.path("excludedSpells").asInt())
                .as("the warm-up spell is left-censored (excluded, by design) — nothing else should"
                        + " confound these spells (no bulk/audit retry was ever submitted)")
                .isEqualTo(1);
        assertThat(selfHeal.path("wilsonLow").asDouble()).isGreaterThanOrEqualTo(0.70);
        assertThat(selfHeal.path("lane").asText())
                .as("RETRYING-RISK-LANE.md §7.2/G12: the LIKELY lane, unreachable before this fixture")
                .isEqualTo("SELF_HEAL_LIKELY");
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
