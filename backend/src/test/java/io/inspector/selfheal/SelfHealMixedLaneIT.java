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
 * Issue #359: the {@code SELF_HEAL_MIXED} boundary — the second lane the design's panel review
 * flagged as unreachable end-to-end (RETRYING-RISK-LANE.md §7.2/G12), and the ONE lane whose
 * math depends on BOTH spell outcomes existing for the same class. {@code floor} is lowered to
 * 3 (property override — the production default of 10 would just take longer to demonstrate
 * the SAME boundary, not a different one; see {@code SelfHealLikelyLaneIT} for the un-lowered
 * floor case).
 *
 * <p>One throwaway warm-up spell (structurally left-censored — see {@code SelfHealLikelyLaneIT}'s
 * identical warm-up for why), then two spells SELF-HEAL (same mechanism: heal directly over REST
 * before retries exhaust) and one ESCALATES (deliberately left un-healed until its retries
 * exhaust into the dead-letter lane — a genuine, organic escalation, not a simulated one). For
 * n=3, healed=2 the Wilson 95% score interval is LB≈0.208 / UB≈0.939 — below the 0.70 LIKELY
 * enter threshold AND above the 0.30 UNLIKELY one, so {@code SelfHealStatsComputer.enterLane}
 * has exactly one lane left: MIXED (RETRYING-RISK-LANE.md §4.1). {@code dwell-cycles=1}: this
 * test is about the boundary computation, not the dwell mechanism ({@code
 * SelfHealDwellSuppressionIT} owns that).
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
            // Occurrence rows are upserted keyed by (incidentId, bucket) — bucket = wall-clock
            // Instant.now() FLOORED to this width, every sampleOnce() call, regardless of how many
            // calls happen (proven live, 2026-08-04: rapid-fire calls collapsed 12+ intended
            // samples into 2 actual rows at the 60s production default, silently erasing every
            // intermediate spell transition). Shrunk (see BUCKET_WIDTH below, kept in lockstep) so
            // SelfHealSeed#awaitNextBucket can cheaply guarantee each sampleOnce() lands in a
            // fresh bucket — safe because SelfHealStatsService reads the SAME property for its
            // own gap/look-ahead-tolerance math (RetrySpellExtractor#GAP_VOID_BUCKETS=5), so
            // 15s keeps that 75s gap-void threshold comfortably above this harness's observed
            // heal-to-completion latency (~20-45s, executor-acquire-lag dominated).
            "inspector.snapshot.bucket-width=PT15S",
            "inspector.selfheal.floor=3",
            "inspector.selfheal.dwell-cycles=1"
        })
// it-selfheal (LATER = higher precedence) restates the it-actions engine list with a raised
// dlq-scan-cap — see that file's own doc comment for why a `properties=` override can't do this
// (Spring's list-valued binding doesn't merge across sources by index).
@ActiveProfiles({"it-actions", "it-selfheal"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfHealMixedLaneIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";
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
        fixture = SelfHealSeed.deploy(engine, "Mixed", "R3/PT1S");
    }

    @AfterAll
    void deleteTheRunUniqueDeployments() {
        SelfHealSeed.cleanupQuietly(engine, fixture);
    }

    @Test
    void twoHealsAndOneEscalationLandTheMixedLane() throws Exception {
        String baseline = SelfHealSeed.startBaseline(engine, fixture);
        SelfHealSeed.awaitDeadLettered(engine, baseline);
        sampler.sampleOnce(); // first sight

        long incidentId = ourIncident().getId();

        /* ---- spell WARM-UP: self-heal, but structurally excluded (left-censored) — see
        SelfHealLikelyLaneIT's identical warm-up spell for why: the DB-side spell-shape
        query drops the quiet row before a brand-new incident's first-ever spell, so that
        spell always lands at array index 0 (RetrySpellExtractor: leftCensored). Absorbing
        it here is what makes spells A/B/C below start at a non-zero index. ---- */
        selfHealOneSpell();
        /* ---- spell A: self-heal ---- */
        selfHealOneSpell();
        /* ---- spell B: self-heal ---- */
        selfHealOneSpell();
        /* ---- spell C: escalate — deliberately never healed ---- */
        String escalating = SelfHealSeed.startTransient(engine, fixture);
        SelfHealSeed.awaitRetrying(engine, escalating);
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce(); // spell C START — also spell B's look-ahead
        SelfHealSeed.awaitDeadLettered(engine, escalating); // retries exhaust: a REAL escalation
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce(); // spell C END (retryingCount=0, dlq now baseline+1)
        SelfHealSeed.awaitNextBucket(BUCKET_WIDTH);
        sampler.sampleOnce(); // spell C's own look-ahead

        JsonNode selfHeal = selfHealJson(incidentId);
        assertThat(selfHeal.path("n").asInt()).isEqualTo(3);
        assertThat(selfHeal.path("healed").asInt()).isEqualTo(2);
        assertThat(selfHeal.path("excludedSpells").asInt())
                .as("the warm-up spell is left-censored (excluded, by design)")
                .isEqualTo(1);
        double wilsonLow = selfHeal.path("wilsonLow").asDouble();
        double wilsonHigh = selfHeal.path("wilsonHigh").asDouble();
        assertThat(wilsonLow).isLessThan(0.70);
        assertThat(wilsonHigh).isGreaterThan(0.30);
        assertThat(selfHeal.path("lane").asText())
                .as("RETRYING-RISK-LANE.md §7.2/G12: the MIXED lane, unreachable before this fixture")
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
