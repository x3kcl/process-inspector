package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.registry.EngineHealthService;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
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
 * Rung 4 (v1.x #7) against the flowable-7 profile: the fifth job queue over the REAL External
 * Worker REST API. Proves the capable path end to end — the BFF FETCHES the external-worker
 * job (from the {@code /external-job-api} sibling context, not management), maps its fields,
 * surfaces the count in vitals, and reflects the LOCK OWNER once a worker acquires it (the crux
 * for a "stuck external worker" incident). The pre-6.8 refusal is {@link ExternalWorkerJobLegacyIT}.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile flowable-7 up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_7_PASSWORD=test")
@ActiveProfiles("it7")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NoDbTestSupport.class)
class ExternalWorkerJob7IT {

    private static final String ENGINE = "http://localhost:8083/flowable-rest/service";
    private static final String ENGINE_ID = "engine-7";
    private static final String TOPIC = "inspectorDemo";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    EngineHealthService healthService;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private String pid;

    @BeforeAll
    void seed() {
        rest = rest.withBasicAuth("admin", "dev");
        engine = EngineSeed.requireReachable(ENGINE, "--profile flowable-7");
        EngineSeed.deployIfMissing(engine, "demoExternalWorker", EngineSeed.EXTERNAL_WORKER_BPMN);
        pid = EngineSeed.startInstance(engine, "demoExternalWorker", null, List.of());
        healthService.probeAll(); // populate capabilities so the BFF gate opens
    }

    @AfterAll
    void cleanup() {
        EngineSeed.deleteInstanceQuietly(engine, pid); // KEEP-up residue hygiene
    }

    @Test
    void fetchesTheExternalWorkerJobMapsItsFieldsAndReflectsTheLockOwner() throws Exception {
        // (1) unacquired: the job is visible in the fifth queue with its activity, no lock owner.
        JsonNode jobs = mapper.readTree(rest.getForObject(url("/jobs/external-worker"), String.class));
        assertThat(jobs.isArray()).isTrue();
        JsonNode mine = row(jobs);
        assertThat(mine.get("elementId").asText()).isEqualTo("chargeViaWorker");
        assertThat(mine.get("retries").asInt()).isEqualTo(3);
        assertThat(mine.hasNonNull("lockOwner")).isFalse(); // no worker has picked it up yet

        // (2) vitals surfaces the count in the diagnostic summary (Task 2).
        JsonNode vitals = mapper.readTree(rest.getForObject(url(""), String.class));
        assertThat(vitals.get("externalWorkerJobs").asInt()).isGreaterThanOrEqualTo(1);

        // (3) a worker acquires the topic → the BFF now reflects the lock owner on THIS instance.
        String worker = "inspector-ew-it-7";
        int acquired = EngineSeed.acquireExternalWorkerJobs(ENGINE, TOPIC, worker, 10);
        assertThat(acquired).isGreaterThanOrEqualTo(1);

        JsonNode locked = row(mapper.readTree(rest.getForObject(url("/jobs/external-worker"), String.class)));
        assertThat(locked.get("lockOwner").asText()).isEqualTo(worker);
        assertThat(locked.hasNonNull("lockExpirationTime")).isTrue();
    }

    private String url(String suffix) {
        return "/api/instances/" + ENGINE_ID + "/" + pid + suffix;
    }

    /** The BFF already scoped the query to THIS instance (processInstanceId filter), so the
     *  response only carries this instance's external-worker job(s) — take the first. */
    private JsonNode row(JsonNode jobs) {
        assertThat(jobs).as("this instance's external-worker jobs").isNotEmpty();
        return jobs.get(0);
    }
}
