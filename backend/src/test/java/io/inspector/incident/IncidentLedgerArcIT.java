package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.snapshot.SnapshotSampler;
import io.inspector.support.EngineSeed;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The R-BAU-10 end-to-end proof (INCIDENT-LEDGER.md §9, issue #261): the FULL incident
 * lifecycle against real flowable-rest 6.8 and a real Postgres — seeded failing process →
 * incident OPEN (live episode + occurrence) → error-class bulk retry-all (visible as a related
 * bulk job on the incident detail) → DLQ drain → resolve → reopen → resolve → a zero-observing
 * cycle arms the regression gate → re-seeded failure → REGRESSED (new episode, regression
 * audit). Unlike {@code IncidentLedgerIT} (synthetic samples), every observation here flows
 * through the REAL pipeline: engine DLQ → triage aggregation → {@code AggregationSampledEvent}
 * → ledger, and the verbs go through the REAL HTTP doors.
 *
 * <p><b>Determinism on the long-lived KEEP-up stack</b> (engine-harness skill):
 * <ul>
 *   <li><b>Run-unique signature.</b> Incident identity is FLEET-WIDE per {@code (hash,
 *       algoVersion)}, so a shared-signature fixture ("/ by zero" residue from sibling ITs, or
 *       a parallel session) could hold the group live and starve the zero-state gate forever.
 *       The seed is therefore a per-run GENERATED variant of the error-zoo missing-property
 *       process whose failing expression carries a run-unique LETTERS-ONLY token
 *       ({@code ${itarcxxxxxxx}}): R-SEM-03 sanitization collapses quoted literals, UUIDs, hex
 *       and digit runs to {@code #} — an unquoted alphabetic token is the one shape that
 *       SURVIVES into the normalized message, keeping the signature unique AND recognizable.</li>
 *   <li><b>Stable hash across cycles</b> — now a PRODUCT guarantee, not a test workaround.
 *       This class used to pin {@code stacktrace-sample-cap: 0} because whether a small group
 *       got refined (hash = {@code FlowableException|msg}) or stayed snippet-only (hash =
 *       {@code |msg}) could flip BETWEEN cycles and mint two incidents mid-arc. Algo v2
 *       (#270) makes identity snippet-only for EVERY group at ANY cap, so the pin is gone and
 *       the arc runs at the production default — which is the point: that same instability
 *       was hitting real users on the demo, and a test that configures its way around a
 *       product defect proves nothing about the product.</li>
 *   <li><b>Deterministic cycles.</b> The sampler bean stays enabled (the arc drives
 *       {@link SnapshotSampler#sampleOnce()} directly — each call is a synchronous fresh
 *       aggregation + ledger ingest, so post-cycle asserts need no Awaitility), but the
 *       SCHEDULED cadence is pinned to 1h (initial delay = interval) so no background cycle
 *       can interleave a zero-state sweep mid-arc. The one startup sample (ApplicationReady)
 *       only ever sees pre-existing residue — the unique token does not exist yet.</li>
 *   <li><b>Healable failure.</b> A missing-identifier expression dead-letters organically
 *       (R1/PT1S — seconds, not the 3×10s default ladder) and is healed by CREATING the
 *       variable over REST, so "retry-all → the class actually drains" is real: the retried
 *       job succeeds and the instance COMPLETES. A permanently-failing fixture would just
 *       re-dead-letter and the drain step could never pass.</li>
 * </ul>
 *
 * <p>Awaitility bounds only wait on ENGINE-side asynchrony (dead-letter transition ≤30s for
 * R1/PT1S incl. executor acquire lag; retry execution ≤45s; the bulk fan-out ≤30s via the
 * BFF's own job read) and never wrap a mutation. LOCAL-ONLY (failsafe *IT, it-actions family,
 * NOT in ci.yml's itClass — like IncidentLedgerIT/BulkErrorClassIT).
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ENGINE_A_PASSWORD=test",
            // scheduler idle (initial delay = interval); cycles are driven by hand below
            "inspector.snapshot.sample-interval=PT1H"
            // NO stacktrace-sample-cap pin: algo v2 keeps identity stable at the production
            // default (#270) — see class doc "Stable hash across cycles".
        })
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentLedgerArcIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";
    private static final Path ZOO_BPMN = Path.of("..", "docker", "processes", "error-zoo-missing-property.bpmn20.xml");

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

    @Autowired
    IncidentEpisodeRepository episodes;

    @Autowired
    IncidentOccurrenceRepository occurrences;

    @Autowired
    JdbcTemplate jdbc;

    private RestClient engine;
    private String token; // run-unique, letters-only — survives R-SEM-03 sanitization
    private String definitionKey; // run-unique — version-1-scoped bulk retry, zero residue overlap

    @BeforeAll
    void deployTheRunUniqueFailingProcess() throws Exception {
        engine = EngineSeed.requireReachable(ENGINE, "");
        token = "itarc" + randomLetters(10);
        definitionKey = "itIncidentArc" + randomLetters(6);
        String xml = Files.readString(ZOO_BPMN)
                .replace("zooMissingProperty", definitionKey) // process id + DI ids
                .replace("ghost.total", token); // the failing expression → ${<token>}
        deploy(definitionKey, xml);
    }

    @AfterAll
    @SuppressWarnings("unchecked")
    void deleteTheRunUniqueDeployments() {
        // KEEP-up hygiene (EngineSeed doctrine): cascade-delete OUR deployments so no
        // dead-letter residue of this run skews a sibling's or a parallel session's scans.
        try {
            Map<String, Object> page = engine.get()
                    .uri("/repository/process-definitions?key=" + definitionKey + "&size=100")
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
            if (data == null) {
                return;
            }
            data.stream()
                    .map(d -> String.valueOf(d.get("deploymentId")))
                    .distinct()
                    .forEach(dep -> {
                        try {
                            engine.delete()
                                    .uri("/repository/deployments/" + dep + "?cascade=true")
                                    .retrieve()
                                    .toBodilessEntity();
                        } catch (RuntimeException e) {
                            // best-effort residue cleanup
                        }
                    });
        } catch (RuntimeException e) {
            // best-effort residue cleanup
        }
    }

    @Test
    void theFullLifecycleArcFromSeedThroughRegression() throws Exception {
        /* ---- 1. seed: two organically dead-lettering instances (R1/PT1S) ---- */
        String first = EngineSeed.startInstance(engine, definitionKey, "it-arc-1", List.of());
        String second = EngineSeed.startInstance(engine, definitionKey, "it-arc-2", List.of());
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> EngineSeed.deadLetterCountFor(engine, first) == 1
                        && EngineSeed.deadLetterCountFor(engine, second) == 1);

        /* ---- 2. one cycle → incident OPEN + live episode + occurrence ---- */
        sampler.sampleOnce(); // synchronous: aggregation + event + ledger ingest all complete here
        Incident row = ourIncident();
        assertThat(row.getState()).isEqualTo(IncidentState.OPEN);
        // the it-actions registry maps the SAME physical engine twice (engine-a + engine-a-prod),
        // so the fleet-wide group total counts each dead letter once PER REGISTRATION: 2 × 2 = 4
        assertThat(row.getLastTotal()).isEqualTo(4);
        assertThat(row.getCountsByEngine()).contains("engine-a").contains(definitionKey);
        long id = row.getId();
        List<IncidentEpisode> eps = episodes.findByIncidentIdOrderByStartedAtDesc(id);
        assertThat(eps).singleElement().satisfies(ep -> {
            assertThat(ep.getStartState()).isEqualTo(IncidentState.OPEN);
            assertThat(ep.getEndedAt()).isNull();
        });
        assertThat(occurrences.findByIdIncidentIdOrderByIdSampledAtAsc(id)).isNotEmpty();
        assertThat(detailJson(id).path("relatedBulkJobs")).isEmpty(); // nothing submitted yet

        /* ---- 3. heal + retry-all via the error-class bulk door ---- */
        heal(first);
        heal(second);
        ResponseEntity<String> submit = as("responder")
                .postForEntity(
                        "/api/bulk/error-class",
                        Map.of(
                                "signatureHash",
                                row.getSignatureHash(),
                                "algoVersion",
                                row.getAlgoVersion(),
                                "processDefinitionKey",
                                definitionKey,
                                "definitionVersion",
                                1,
                                "engineId",
                                "engine-a",
                                "reason",
                                "IT arc: retry the healed error class"),
                        String.class);
        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.OK);
        String jobId = mapper.readTree(submit.getBody()).path("id").asText();
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    JsonNode job = mapper.readTree(as("responder")
                            .getForEntity("/api/bulk/" + jobId, String.class)
                            .getBody());
                    assertThat(job.path("state").asText()).isEqualTo("COMPLETED");
                    assertThat(job.path("tallies").path("ok").asLong()).isEqualTo(2);
                });

        /* ---- 4. the related-bulk-jobs join surfaces the retry on the detail ---- */
        JsonNode related = detailJson(id).path("relatedBulkJobs");
        assertThat(related).hasSize(1);
        assertThat(related.get(0).path("id").asText()).isEqualTo(jobId);
        assertThat(related.get(0).path("scopeKind").asText()).isEqualTo("ERROR_CLASS");
        assertThat(related.get(0).path("verb").asText()).isEqualTo("retry-job");
        assertThat(related.get(0).path("tallies").path("ok").asLong()).isEqualTo(2);

        /* ---- 5. the retried jobs SUCCEED (healed) → instances complete → DLQ drains ---- */
        // ≤45s: async-executor acquire lag dominates (the retry itself is instant once acquired)
        await().atMost(45, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> EngineSeed.deadLetterCountFor(engine, first) == 0
                        && EngineSeed.deadLetterCountFor(engine, second) == 0
                        && runtimeGone(first)
                        && runtimeGone(second));

        /* ---- 6. resolve → reopen → resolve (the human verbs, over HTTP, audited) ---- */
        ResponseEntity<String> resolve1 = as("operator")
                .postForEntity(
                        "/api/incidents/" + id + "/resolve",
                        Map.of("reason", "IT arc: healed the variable and drained the class"),
                        String.class);
        assertThat(resolve1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reload(id).getState()).isEqualTo(IncidentState.RESOLVED);
        assertThat(episodes.findFirstByIncidentIdAndEndedAtIsNullOrderByStartedAtDesc(id))
                .isEmpty(); // the live episode was closed by the resolve

        ResponseEntity<String> reopen = as("operator")
                .postForEntity(
                        "/api/incidents/" + id + "/reopen",
                        Map.of("reason", "IT arc: resolved too early, taking another look"),
                        String.class);
        assertThat(reopen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reload(id).getState()).isEqualTo(IncidentState.OPEN);
        assertThat(episodes.findFirstByIncidentIdAndEndedAtIsNullOrderByStartedAtDesc(id))
                .isPresent(); // reopen revives the LAST episode — no new one minted
        assertThat(episodes.findByIncidentIdOrderByStartedAtDesc(id)).hasSize(1);

        ResponseEntity<String> resolve2 = as("operator")
                .postForEntity(
                        "/api/incidents/" + id + "/resolve",
                        Map.of("reason", "IT arc: confirmed drained, resolving for good"),
                        String.class);
        assertThat(resolve2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reload(id).getState()).isEqualTo(IncidentState.RESOLVED);
        assertThat(auditCount(IncidentLifecycleService.ACTION_RESOLVE)).isEqualTo(2);
        assertThat(auditCount(IncidentLifecycleService.ACTION_REOPEN)).isEqualTo(1);

        /* ---- 7. a cycle that observes the class ABSENT arms the regression gate ---- */
        assertThat(reload(id).isSeenZeroSinceResolve())
                .as("resolve resets the gate — it must arm from OBSERVATION, never from the verb")
                .isFalse();
        sampler.sampleOnce(); // the class is drained — this cycle sees it absent
        assertThat(reload(id).isSeenZeroSinceResolve()).isTrue();

        /* ---- 8. the failure RETURNS → next cycle → REGRESSED, new episode, audited ---- */
        String reseeded = EngineSeed.startInstance(engine, definitionKey, "it-arc-3", List.of());
        try {
            await().atMost(30, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> EngineSeed.deadLetterCountFor(engine, reseeded) == 1);
            sampler.sampleOnce();

            Incident regressed = reload(id);
            assertThat(regressed.getState()).isEqualTo(IncidentState.REGRESSED);
            assertThat(regressed.getRegressionCount()).isEqualTo(1);
            assertThat(regressed.getLastRegressedAt()).isNotNull();
            assertThat(regressed.isSeenZeroSinceResolve()).isFalse(); // consumed by the transition

            List<IncidentEpisode> after = episodes.findByIncidentIdOrderByStartedAtDesc(id);
            assertThat(after).hasSize(2); // reopen recycled ep.1 — REGRESSED minted ep.2
            assertThat(after.get(0).getStartState()).isEqualTo(IncidentState.REGRESSED);
            assertThat(after.get(0).getEndedAt()).isNull();
            assertThat(after.get(1).getEndedAt()).isNotNull();

            assertThat(auditCount(IncidentLedgerService.ACTION_REGRESSED))
                    .as("exactly ONE regression config-event for this signature")
                    .isEqualTo(1);
        } finally {
            EngineSeed.deleteInstanceQuietly(engine, reseeded);
        }
    }

    /* ---------------- helpers ---------------- */

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    /** The run's OWN incident, keyed by the surviving token in the normalized message. */
    private Incident ourIncident() {
        List<Incident> matching = incidents.findAll().stream()
                .filter(i -> i.getNormalizedMessage() != null
                        && i.getNormalizedMessage().contains(token))
                .toList();
        assertThat(matching)
                .as("exactly one incident for token %s — a second row would mean hash instability", token)
                .hasSize(1);
        return matching.get(0);
    }

    private Incident reload(long id) {
        return incidents.findById(id).orElseThrow();
    }

    private JsonNode detailJson(long id) throws Exception {
        ResponseEntity<String> detail = as("viewer").getForEntity("/api/incidents/" + id, String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(detail.getBody());
    }

    /** Heals the failing expression by CREATING the referenced variable — the retry then succeeds. */
    private void heal(String processInstanceId) {
        engine.post()
                .uri("/runtime/process-instances/" + processInstanceId + "/variables")
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("name", token, "type", "string", "value", "healed")))
                .retrieve()
                .toBodilessEntity();
    }

    /** True once the runtime instance is gone (completed) — Flowable answers 404. */
    private boolean runtimeGone(String processInstanceId) {
        try {
            engine.get()
                    .uri("/runtime/process-instances/" + processInstanceId)
                    .retrieve()
                    .toBodilessEntity();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = ? AND payload::text LIKE ?",
                Integer.class,
                action,
                "%" + ourIncident().getSignatureHash() + "%");
        return count != null ? count : 0;
    }

    private void deploy(String key, String xml) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return key + ".bpmn20.xml";
            }
        });
        engine.post()
                .uri("/repository/deployments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toBodilessEntity();
        // validate-bpmn §3: deployment success is the definition APPEARING, never the 2xx
        assertThat(EngineSeed.definitionCount(engine, key))
                .as("generated process '%s' deployed and parsed", key)
                .isEqualTo(1);
    }

    /** Letters the R-SEM-03 sanitizer can never collapse (no digits, no a-f hex ambiguity). */
    private static String randomLetters(int n) {
        String alphabet = "ghjkmnpqrstuvwxyz";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
