package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Rung 4, the R-SEM-20 / TS-STAT-16 <b>6.3 null gate</b>, proven against the REAL pre-cliff
 * Flowable 6.3.1 engine — the sibling of {@link TriageCmmnScopeIT} (6.8, gate OPEN) that the
 * register, TEST-SCENARIOS §2 and TEST-STRATEGY §6 name but which did not exist until now.
 *
 * <p>6.3.1 is CMMN-dead-letter-blind: its process-api DLQ projection serializes no
 * {@code scopeType} discriminator, so the inspector <b>cannot</b> tell a shared-table CMMN
 * orphan from a BPMN dead-letter. The honesty contract (R-SEM-20) is that the count is then
 * {@code null} — <i>unknown</i>, never a confident {@code 0} that would falsely reconcile the
 * dead-letter lane. This suite pins that gate closed against a live engine whose DLQ lane is
 * demonstrably NON-empty (a real BPMN payment is dead-lettered first), so the {@code null} is
 * proven to be a capability gate, not merely an empty lane. The synthetic rung-1 mirror is
 * {@code OutOfScopeDeadlettersTest.returnsNullCountWhenTheEngineCannotDiscriminateScope}.
 *
 * <p>Requires: docker compose -f docker/docker-compose.dev.yml --profile legacy up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_LEGACY_PASSWORD=test")
@ActiveProfiles("it-legacy-triage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NoDbTestSupport.class)
class TriageCmmnScopeLegacyIT {

    private static final String ENGINE = "http://localhost:"
            + System.getenv().getOrDefault("PI_ENGINE_LEGACY_PORT", "8084") + "/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    private RestClient engine;

    @BeforeAll
    void seedOrganically() {
        rest = rest.withBasicAuth("admin", "dev"); // the BFF requires auth on every /api route
        engine = EngineSeed.requireReachable(ENGINE, "--profile legacy");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        String failing = EngineSeed.startFailingPayment(engine);

        // A REAL, non-empty BPMN dead-letter lane — so a null out-of-scope count can only be
        // the capability gate closing, never "the lane happened to be empty".
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, failing))
                        .isGreaterThanOrEqualTo(1));

        // The scopeType capability is set by the scheduled M1 probe — wait one cycle so triage
        // reads a populated registry, not a cold one.
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(rest.getForObject("/api/engines", JsonNode.class)
                                .get(0)
                                .get("reachable")
                                .asBoolean())
                        .isTrue());
    }

    @Test
    void outOfScopeDeadletterCountIsNullOnThePreCliffEngineNeverAConfidentZero() {
        JsonNode body = rest.getForObject("/api/triage?refresh=true", JsonNode.class);

        // The engine cannot discriminate scope — the capability gate is CLOSED on 6.3.1.
        assertThat(body.get("engines")
                        .get(0)
                        .get("capabilities")
                        .path("scopeType")
                        .asBoolean(false))
                .as("engine-legacy is Flowable 6.3.1 — no scopeType discriminator")
                .isFalse();

        JsonNode perEngine = body.get("perEngine").get("engine-legacy");
        assertThat(perEngine.get("ok").asBoolean()).isTrue();

        // NULL, not 0: unknown is honest, a confident zero would be a quiet lie (R-SEM-20).
        // The lane is non-empty (we dead-lettered a BPMN payment), so this null is purely the
        // capability gate — the count cannot be trusted, so it is withheld.
        JsonNode outOfScope = perEngine.path("outOfScopeDeadletters");
        assertThat(outOfScope.isNull())
                .as("a scope-blind engine reports the out-of-scope count as unknown (null), never 0")
                .isTrue();
    }
}
