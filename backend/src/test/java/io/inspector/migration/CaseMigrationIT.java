package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.bulk.BulkJob;
import io.inspector.bulk.BulkJobItem;
import io.inspector.bulk.BulkJobItemRepository;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.support.EngineSeed;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): case migration (#500) against REAL flowable-rest 6.8 ({@code engine-a},
 * plus its prod twin) and 7.1 ({@code engine-7}, plus its prod twin), with a real Postgres. Seeds over the cmmn REST
 * API only. Each test deploys its own two versions of {@code demoCaseMigration}, so a cohort is
 * exactly the cases that test started.
 *
 * <p>The fixture: version 1 has three human tasks (reviewTask, extraCheck, optionalNote); version
 * 2 drops optionalNote. A case whose optionalNote is still open is a blocker; one that completed
 * it is clean; completing extraCheck afterwards changes the live plan-item set without making the
 * case a blocker.
 *
 * <ul>
 *   <li>6.8: single case, the case cohort through the bulk machinery (blocker never sent, two
 *       kinds of "changed since the analysis" skipped, typed count on prod), "Continue as new
 *       job" and "Verify now" for the verb through {@code /api/bulk}, and the 7.x-only routes
 *       refused with {@code version-gate} before any engine contact.
 *   <li>7.1: single case, cohort with the {@code contains} filter, by-definition batch, historic
 *       migration (one case and by definition), and the on-ramp counts; the batch pre-check on a
 *       single-quoted source model; a failed batch part; the typed confirmation of all three
 *       doors on the prod twin.
 * </ul>
 *
 * <p>CI: the {@code migration-findings} leg (flowable-6 + flowable-7 + postgres). Locally:
 * {@code COMPOSE_PROFILES=flowable-6,flowable-7,postgres docker compose -f
 * docker/docker-compose.dev.yml up -d}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ENGINE_A_PASSWORD=test", "ENGINE_7_PASSWORD=test"})
@ActiveProfiles("it-case-migration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureTestRestTemplate
class CaseMigrationIT {

    private static final String KEY = "demoCaseMigration";
    private static final Path V1 = Path.of("..", "docker", "processes", "demo-case-migration-v1.cmmn.xml");
    private static final Path V2 = Path.of("..", "docker", "processes", "demo-case-migration-v2.cmmn.xml");
    private static final String REASON = "CaseMigrationIT issue 500 move cases to the fixed model";

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
    AuditEntryRepository audit;

    @Autowired
    BulkJobRepository bulkJobs;

    @Autowired
    BulkJobItemRepository bulkItems;

    private RestClient cmmn6;
    private RestClient cmmn7;
    private RestClient process7;
    private final List<String> deployments6 = new ArrayList<>();
    private final List<String> deployments7 = new ArrayList<>();

    @BeforeAll
    void engines() {
        String six = url("PI_ENGINE_A_PORT", "8081");
        String seven = url("PI_ENGINE_7_PORT", "8083");
        EngineSeed.requireReachable(six, "");
        EngineSeed.requireReachable(seven, "--profile flowable-7");
        cmmn6 = EngineSeed.cmmnClient(six);
        cmmn7 = EngineSeed.cmmnClient(seven);
        process7 = EngineSeed.engineClient(seven);
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> cmmnCapable("engine-a")
                        && cmmnCapable("engine-a-prod")
                        && cmmnCapable("engine-7")
                        && cmmnCapable("engine-7-prod"));
    }

    /**
     * Hermetic teardown: only the deployments this class made, cascade (their cases go with them),
     * and the engine batch records whose source is one of those deployments' definitions.
     */
    @AfterAll
    void undeploy() {
        deployments7.forEach(id -> deleteBatchesQuietly(process7, cmmn7, id));
        deployments6.forEach(id -> deleteDeploymentQuietly(cmmn6, id));
        deployments7.forEach(id -> deleteDeploymentQuietly(cmmn7, id));
    }

    /* ------------------------------- 6.8 ------------------------------- */

    @Test
    void singleCaseMigratesOnSixEightAndABlockerIsRefusedBeforeTheEngine() throws Exception {
        String from = deploy(cmmn6, deployments6, V1);
        String to = deploy(cmmn6, deployments6, V2);
        String clean = startCase(cmmn6, from, "ISSUE500-single-clean");
        completeTask(cmmn6, clean, "optionalNote");
        String blocked = startCase(cmmn6, from, "ISSUE500-single-blocked");

        ResponseEntity<String> refused = migrateCase("engine-a", blocked, to, null);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(code(refused)).isEqualTo("plan-item-not-in-target");
        assertThat(refused.getBody()).contains("optionalNote");
        assertThat(caseDefinitionOf(cmmn6, blocked)).isEqualTo(from);

        ResponseEntity<String> ok = migrateCase("engine-a", clean, to, null);
        assertThat(ok.getStatusCode()).as(ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(caseDefinitionOf(cmmn6, clean)).isEqualTo(to);

        JsonNode payload = auditPayload("engine-a", clean, "migrate-case");
        assertThat(payload.path("schema").asText()).isEqualTo("migrate-case/v1");
        assertThat(payload.path("fromCaseDefinitionId").asText()).isEqualTo(from);
        assertThat(payload.path("toCaseDefinitionId").asText()).isEqualTo(to);
        assertThat(ids(payload.path("activePlanItemDefinitionIds")))
                .containsExactlyInAnyOrder("reviewTask", "extraCheck");
    }

    @Test
    void caseCohortOnSixEightRunsThroughTheBulkMachineryWithEveryRail() throws Exception {
        String from = deploy(cmmn6, deployments6, V1);
        String to = deploy(cmmn6, deployments6, V2);
        List<String> clean = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = startCase(cmmn6, from, "ISSUE500-cohort-clean-" + i);
            completeTask(cmmn6, id, "optionalNote");
            clean.add(id);
        }
        String blocked = startCase(cmmn6, from, "ISSUE500-cohort-blocked");
        String movedElsewhere = startCase(cmmn6, from, "ISSUE500-cohort-moved");
        completeTask(cmmn6, movedElsewhere, "optionalNote");
        String changed = startCase(cmmn6, from, "ISSUE500-cohort-changed");
        completeTask(cmmn6, changed, "optionalNote");

        // The on-ramp counts the cohort before anything is analyzed.
        JsonNode versions = get("/api/case-definitions/engine-a/" + KEY + "/versions");
        assertThat(versionRow(versions, from).path("runningCaseCount").asInt()).isEqualTo(6);
        assertThat(versions.path("definitionRoutesGate").asText()).contains("needs Flowable 7.x");

        // 6.8 ignores businessKeyLike and would return every case: refused, not sent.
        Map<String, Object> contains = filter(from, to);
        contains.put("businessKey", "cohort-clean");
        contains.put("businessKeyContains", true);
        ResponseEntity<String> gated = as("admin")
                .postForEntity("/api/case-migration/cohorts/preview?engineId=engine-a", contains, String.class);
        assertThat(gated.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(gated)).isEqualTo("version-gate");

        ResponseEntity<String> preview = as("admin")
                .postForEntity("/api/case-migration/cohorts/preview?engineId=engine-a", filter(from, to), String.class);
        assertThat(preview.getStatusCode()).as(preview.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(preview.getBody()).path("count").asInt()).isEqualTo(6);

        // The prod twin is the same engine: its analysis needs the typed count at submit.
        JsonNode prodAnalysis = analyze("engine-a-prod", from, to);
        Map<String, Object> prodSubmit = new HashMap<>();
        prodSubmit.put("analysisIds", List.of(prodAnalysis.path("id").asText()));
        prodSubmit.put("reason", REASON);
        prodSubmit.put("ticketId", "INC-500");
        ResponseEntity<String> noCount = as("admin").postForEntity("/api/bulk/migrate-cases", prodSubmit, String.class);
        assertThat(noCount.getStatusCode()).as(noCount.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(noCount)).isEqualTo("bulk-migrate-confirm-count-required");
        prodSubmit.put("confirmedCount", 99);
        ResponseEntity<String> drift = as("admin").postForEntity("/api/bulk/migrate-cases", prodSubmit, String.class);
        assertThat(drift.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(drift)).isEqualTo("bulk-count-drift");

        JsonNode analysis = analyze("engine-a", from, to);
        assertThat(analysis.path("blockerCount").asInt()).isEqualTo(1);
        assertThat(analysis.path("blockers").get(0).path("caseInstanceId").asText())
                .isEqualTo(blocked);
        assertThat(analysis.path("byPlanItem")
                        .get(0)
                        .path("planItemDefinitionId")
                        .asText())
                .isEqualTo("optionalNote");
        assertThat(ids(analysis.path("executableIds")))
                .containsExactlyInAnyOrderElementsOf(concat(clean, movedElsewhere, changed));

        // A case analysis is not a process analysis, and the process bulk door refuses it.
        String analysisId = analysis.path("id").asText();
        assertThat(as("admin")
                        .getForEntity("/api/migration/analyses/" + analysisId, String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as("admin")
                        .postForEntity(
                                "/api/bulk/migrate",
                                Map.of("analysisIds", List.of(analysisId), "reason", REASON),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // After the analysis: one case is migrated out of band, one changes its live plan items.
        engineMigrate(cmmn6, movedElsewhere, to);
        completeTask(cmmn6, changed, "extraCheck");

        ResponseEntity<String> submitted = as("admin")
                .postForEntity(
                        "/api/bulk/migrate-cases",
                        Map.of("analysisIds", List.of(analysisId), "reason", REASON),
                        String.class);
        assertThat(submitted.getStatusCode()).as(submitted.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode job =
                awaitTerminal(mapper.readTree(submitted.getBody()).path("id").asText());

        assertThat(job.path("verb").asText()).isEqualTo("migrate-case");
        assertThat(job.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(job.path("totalItems").asInt()).isEqualTo(5);
        assertThat(instanceIds(job)).doesNotContain(blocked);
        for (String id : clean) {
            assertThat(item(job, id).path("state").asText())
                    .as(item(job, id).toString())
                    .isEqualTo("ok");
            assertThat(caseDefinitionOf(cmmn6, id)).isEqualTo(to);
        }
        assertThat(item(job, movedElsewhere).path("state").asText()).isEqualTo("skipped");
        assertThat(item(job, movedElsewhere).path("detail").asText()).contains("same-definition-target");
        assertThat(item(job, changed).path("state").asText()).isEqualTo("skipped");
        assertThat(item(job, changed).path("detail").asText()).contains("instance-moved-since-preview");
        assertThat(caseDefinitionOf(cmmn6, changed)).isEqualTo(from);
        assertThat(caseDefinitionOf(cmmn6, blocked)).isEqualTo(from);

        // One audit row per migrated case, and none for a case that was skipped before the engine.
        JsonNode payload = auditPayload("engine-a", clean.get(0), "migrate-case");
        assertThat(payload.path("schema").asText()).isEqualTo("migrate-case/v1");
        assertThat(payload.path("toCaseDefinitionId").asText()).isEqualTo(to);
        assertThat(audit.findByEngineIdAndInstanceIdOrderByTsDesc("engine-a", changed, PageRequest.of(0, 5)))
                .noneMatch(row -> "migrate-case".equals(row.getAction()));
    }

    /**
     * The operations drawer's two follow-ups for a {@code migrate-case} job, through the HTTP door
     * and the real engine. An interrupted job and a lost answer cannot be produced on demand
     * against a healthy engine, so the prior job's rows are written through the BFF's own
     * repositories (its own store, never the engine's): one {@code not_run} item and two
     * {@code unknown} ones, all referencing a real analysis.
     */
    @Test
    void continueAsNewJobAndVerifyNowWorkForMigrateCaseThroughTheBulkDoor() throws Exception {
        String from = deploy(cmmn6, deployments6, V1);
        String to = deploy(cmmn6, deployments6, V2);
        String notRun = startCase(cmmn6, from, "ISSUE500-door-not-run");
        String applied = startCase(cmmn6, from, "ISSUE500-door-applied");
        String notApplied = startCase(cmmn6, from, "ISSUE500-door-not-applied");
        for (String id : List.of(notRun, applied, notApplied)) {
            completeTask(cmmn6, id, "optionalNote");
        }
        String analysisId = analyze("engine-a", from, to).path("id").asText();
        // The migrate of this case landed and only its answer was lost.
        engineMigrate(cmmn6, applied, to);

        UUID prior = UUID.randomUUID();
        BulkJob job = new BulkJob(prior, "admin", Instant.now(), "migrate-case", REASON, null, 3, null);
        job.finish(BulkJob.State.INTERRUPTED, Instant.now());
        bulkJobs.saveAndFlush(job);
        bulkItems.saveAllAndFlush(List.of(
                new BulkJobItem(prior, 0, "engine-a", notRun, analysisId, BulkJobItem.State.not_run),
                new BulkJobItem(prior, 1, "engine-a", applied, analysisId, BulkJobItem.State.unknown),
                new BulkJobItem(prior, 2, "engine-a", notApplied, analysisId, BulkJobItem.State.unknown)));

        // -- Continue as new job: POST /api/bulk with the job's own verb --
        Map<String, Object> fresh = new HashMap<>();
        fresh.put("verb", "migrate-case");
        fresh.put("reason", REASON);
        fresh.put("items", List.of(target(notRun, analysisId)));
        ResponseEntity<String> noPrior = as("admin").postForEntity("/api/bulk", fresh, String.class);
        assertThat(noPrior.getStatusCode()).as(noPrior.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(noPrior)).isEqualTo("migrate-needs-analysis");

        Map<String, Object> continuation = new HashMap<>(fresh);
        continuation.put("continuedFrom", prior.toString());
        continuation.put("items", List.of(target(applied, analysisId)));
        ResponseEntity<String> notContinuable = as("admin").postForEntity("/api/bulk", continuation, String.class);
        assertThat(notContinuable.getStatusCode()).as(notContinuable.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(notContinuable)).isEqualTo("continuation-scope-mismatch");
        assertThat(caseDefinitionOf(cmmn6, notRun)).isEqualTo(from);

        continuation.put("items", List.of(target(notRun, analysisId)));
        ResponseEntity<String> resumed = as("admin").postForEntity("/api/bulk", continuation, String.class);
        assertThat(resumed.getStatusCode()).as(resumed.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode second =
                awaitTerminal(mapper.readTree(resumed.getBody()).path("id").asText());
        assertThat(second.path("verb").asText()).isEqualTo("migrate-case");
        assertThat(second.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(second.path("continuedFrom").asText()).isEqualTo(prior.toString());
        assertThat(second.path("totalItems").asInt()).isEqualTo(1);
        assertThat(item(second, notRun).path("state").asText())
                .as(item(second, notRun).toString())
                .isEqualTo("ok");
        assertThat(caseDefinitionOf(cmmn6, notRun)).isEqualTo(to);
        assertThat(auditPayload("engine-a", notRun, "migrate-case")
                        .path("toCaseDefinitionId")
                        .asText())
                .isEqualTo(to);

        // -- Verify now: a read of the live case, never a second migrate --
        ResponseEntity<String> verified =
                as("admin").postForEntity("/api/bulk/" + prior + "/items/1/verify", null, String.class);
        assertThat(verified.getStatusCode()).as(verified.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode nowOk = mapper.readTree(verified.getBody());
        assertThat(nowOk.path("state").asText()).isEqualTo("ok");
        assertThat(nowOk.path("detail").asText()).contains("verified: case is on " + to);

        ResponseEntity<String> stillUnknown =
                as("admin").postForEntity("/api/bulk/" + prior + "/items/2/verify", null, String.class);
        assertThat(stillUnknown.getStatusCode()).as(stillUnknown.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode unknown = mapper.readTree(stillUnknown.getBody());
        assertThat(unknown.path("state").asText()).isEqualTo("unknown");
        assertThat(unknown.path("detail").asText()).contains("case is still on " + from);
        assertThat(caseDefinitionOf(cmmn6, notApplied)).isEqualTo(from);
        assertThat(audit.findByEngineIdAndInstanceIdOrderByTsDesc("engine-a", notApplied, PageRequest.of(0, 5)))
                .noneMatch(row -> "migrate-case".equals(row.getAction()));

        ResponseEntity<String> notUnknown =
                as("admin").postForEntity("/api/bulk/" + prior + "/items/0/verify", null, String.class);
        assertThat(notUnknown.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(notUnknown)).isEqualTo("bulk-item-not-unknown");
    }

    @Test
    void sixEightRefusesTheSevenOnlyRoutesBeforeAnyEngineContact() throws Exception {
        // Ids the engine has never seen: a refusal that named them would prove an engine read.
        Map<String, Object> body = Map.of("toCaseDefinitionId", "issue500-no-such-target", "reason", REASON);
        for (String path : List.of(
                "/api/case-definitions/engine-a/issue500-no-such-source/migrate-batch",
                "/api/case-definitions/engine-a/issue500-no-such-source/migrate-historic",
                "/api/cases/engine-a/issue500-no-such-case/migrate-historic")) {
            ResponseEntity<String> refused = as("admin").postForEntity(path, body, String.class);
            assertThat(refused.getStatusCode()).as(path).isEqualTo(HttpStatus.CONFLICT);
            assertThat(code(refused)).as(path).isEqualTo("version-gate");
            JsonNode problem = mapper.readTree(refused.getBody());
            assertThat(problem.path("detail").asText())
                    .as(path)
                    .contains("Flowable 7.x version gate")
                    .contains("6.8.0")
                    .contains("Refused before any engine call");
        }
        assertThat(get("/api/case-definitions/engine-a/issue500-no-such-source/migration-batches"))
                .isEmpty();
    }

    /* ------------------------------- 7.1 ------------------------------- */

    @Test
    void sevenOneMigratesASingleCaseACohortABatchAndHistory() throws Exception {
        String v1 = deploy(cmmn7, deployments7, V1);
        String v2 = deploy(cmmn7, deployments7, V2);
        String v3 = deploy(cmmn7, deployments7, V2); // the same model again: a pure version bump

        // -- single case --
        String single = startCase(cmmn7, v1, "ISSUE500-seven-single");
        completeTask(cmmn7, single, "optionalNote");
        ResponseEntity<String> ok = migrateCase("engine-7", single, v2, null);
        assertThat(ok.getStatusCode()).as(ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(caseDefinitionOf(cmmn7, single)).isEqualTo(v2);

        // -- cohort, with the 7.x-only contains filter --
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String id = startCase(cmmn7, v1, "ISSUE500-seven-pick-" + i);
            completeTask(cmmn7, id, "optionalNote");
            picked.add(id);
        }
        String blocked = startCase(cmmn7, v1, "ISSUE500-seven-pick-blocked");
        String outside = startCase(cmmn7, v1, "ISSUE500-seven-other");
        completeTask(cmmn7, outside, "optionalNote");
        Map<String, Object> filter = filter(v1, v2);
        filter.put("businessKey", "seven-pick");
        filter.put("businessKeyContains", true);
        JsonNode analysis = analyze("engine-7", filter);
        assertThat(analysis.path("cohortTotal").asInt()).isEqualTo(3);
        assertThat(ids(analysis.path("executableIds"))).containsExactlyInAnyOrderElementsOf(picked);
        ResponseEntity<String> submitted = as("admin")
                .postForEntity(
                        "/api/bulk/migrate-cases",
                        Map.of("analysisIds", List.of(analysis.path("id").asText()), "reason", REASON),
                        String.class);
        assertThat(submitted.getStatusCode()).as(submitted.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode job =
                awaitTerminal(mapper.readTree(submitted.getBody()).path("id").asText());
        assertThat(job.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(job.path("totalItems").asInt()).isEqualTo(2);
        for (String id : picked) {
            assertThat(item(job, id).path("state").asText())
                    .as(item(job, id).toString())
                    .isEqualTo("ok");
            assertThat(caseDefinitionOf(cmmn7, id)).isEqualTo(v2);
        }
        assertThat(caseDefinitionOf(cmmn7, blocked)).isEqualTo(v1);
        assertThat(caseDefinitionOf(cmmn7, outside)).isEqualTo(v1);

        // -- by-definition batch --
        // v1 → v2 drops optionalNote: a batch has no per-case check, so the BFF does not send it.
        ResponseEntity<String> lossy = as("admin")
                .postForEntity(
                        "/api/case-definitions/engine-7/" + v1 + "/migrate-batch",
                        Map.of("toCaseDefinitionId", v2, "reason", REASON),
                        String.class);
        assertThat(lossy.getStatusCode().value()).isEqualTo(422);
        assertThat(code(lossy)).isEqualTo("plan-item-not-in-target");
        assertThat(caseDefinitionOf(cmmn7, outside)).isEqualTo(v1);

        // v2 → v3 keeps every plan-item definition: the three cases now on v2 move as one batch.
        ResponseEntity<String> batch = as("admin")
                .postForEntity(
                        "/api/case-definitions/engine-7/" + v2 + "/migrate-batch",
                        Map.of("toCaseDefinitionId", v3, "reason", REASON),
                        String.class);
        assertThat(batch.getStatusCode()).as(batch.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(batch.getBody()).path("deltaStatement").asText())
                .contains("3 running cases")
                .contains("batch");
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    JsonNode batches = get("/api/case-definitions/engine-7/" + v2 + "/migration-batches");
                    assertThat(batches).hasSize(1);
                    assertThat(batches.get(0).path("toCaseDefinitionId").asText())
                            .isEqualTo(v3);
                    assertThat(batches.get(0).path("parts").asInt()).isEqualTo(3);
                    assertThat(batches.get(0)
                                    .path("partsByStatus")
                                    .path("success")
                                    .asInt())
                            .isEqualTo(3);
                    assertThat(caseDefinitionOf(cmmn7, single)).isEqualTo(v3);
                    assertThat(caseDefinitionOf(cmmn7, picked.get(0))).isEqualTo(v3);
                    assertThat(caseDefinitionOf(cmmn7, picked.get(1))).isEqualTo(v3);
                });
        JsonNode batchAudit = auditPayload("engine-7", null, CaseDefinitionMigrationService.BATCH_ACTION);
        assertThat(batchAudit.path("schema").asText()).isEqualTo("migrate-case/v1");
        assertThat(batchAudit.path("route").asText()).isEqualTo("by-definition-batch");
        assertThat(batchAudit.path("runningCases").asInt()).isEqualTo(3);

        // -- historic --
        String endedOne = startCase(cmmn7, v1, "ISSUE500-seven-ended-1");
        String endedTwo = startCase(cmmn7, v1, "ISSUE500-seven-ended-2");
        for (String id : List.of(endedOne, endedTwo)) {
            for (String task : List.of("reviewTask", "extraCheck", "optionalNote")) {
                completeTask(cmmn7, id, task);
            }
        }
        JsonNode versions = get("/api/case-definitions/engine-7/" + KEY + "/versions");
        assertThat(versions.path("definitionRoutesGate").isNull()).isTrue();
        assertThat(versionRow(versions, v1).path("endedCaseCount").asInt()).isEqualTo(2);
        assertThat(versionRow(versions, v1).path("runningCaseCount").asInt()).isEqualTo(2);
        assertThat(versionRow(versions, v3).path("runningCaseCount").asInt()).isEqualTo(3);

        // A running case is not history: refused in the BFF, the engine would answer 500.
        ResponseEntity<String> running = as("admin")
                .postForEntity(
                        "/api/cases/engine-7/" + outside + "/migrate-historic",
                        Map.of("toCaseDefinitionId", v2, "reason", REASON),
                        String.class);
        assertThat(running.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(running)).isEqualTo("case-still-running");

        ResponseEntity<String> one = as("admin")
                .postForEntity(
                        "/api/cases/engine-7/" + endedOne + "/migrate-historic",
                        Map.of("toCaseDefinitionId", v2, "reason", REASON),
                        String.class);
        assertThat(one.getStatusCode()).as(one.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(historicDefinitionOf(cmmn7, endedOne)).isEqualTo(v2);
        assertThat(historicDefinitionOf(cmmn7, endedTwo)).isEqualTo(v1);
        assertThat(auditPayload("engine-7", endedOne, CaseDefinitionMigrationService.HISTORIC_CASE_ACTION)
                        .path("route")
                        .asText())
                .isEqualTo("historic");

        ResponseEntity<String> rest = as("admin")
                .postForEntity(
                        "/api/case-definitions/engine-7/" + v1 + "/migrate-historic",
                        Map.of("toCaseDefinitionId", v2, "reason", REASON),
                        String.class);
        assertThat(rest.getStatusCode()).as(rest.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(rest.getBody()).path("deltaStatement").asText())
                .contains("1 ended case");
        assertThat(historicDefinitionOf(cmmn7, endedTwo)).isEqualTo(v2);
        // History only: the cases still running on v1 stay there.
        assertThat(caseDefinitionOf(cmmn7, blocked)).isEqualTo(v1);
        assertThat(caseDefinitionOf(cmmn7, outside)).isEqualTo(v1);

        // Nothing ended is left on v1: the door refuses instead of sending an empty migration.
        ResponseEntity<String> empty = as("admin")
                .postForEntity(
                        "/api/case-definitions/engine-7/" + v1 + "/migrate-historic",
                        Map.of("toCaseDefinitionId", v2, "reason", REASON),
                        String.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(empty)).isEqualTo("nothing-to-migrate");
    }

    /**
     * The batch pre-check is the only guard of the by-definition batch (7.1 answers 200 and ends
     * the live plan item), so it must read a source model whatever its attribute quoting. Without
     * it this batch is sent and the case loses optionalNote and its task.
     */
    @Test
    void sevenOneBatchDoorRefusesASingleQuotedSourceModelThatLosesAPlanItem() throws Exception {
        Path singleQuoted = Files.createTempFile("issue500-single-quoted-", ".cmmn.xml");
        try {
            Files.writeString(
                    singleQuoted,
                    Files.readString(V1, StandardCharsets.UTF_8)
                            .replaceAll("definitionRef=\"([^\"]+)\"", "definitionRef='$1'"),
                    StandardCharsets.UTF_8);
            assertThat(Files.readString(singleQuoted)).contains("definitionRef='optionalNote'");
            String from = deploy(cmmn7, deployments7, singleQuoted);
            String to = deploy(cmmn7, deployments7, V2);
            String open = startCase(cmmn7, from, "ISSUE500-seven-single-quoted");

            ResponseEntity<String> refused = as("admin")
                    .postForEntity(
                            "/api/case-definitions/engine-7/" + from + "/migrate-batch",
                            Map.of("toCaseDefinitionId", to, "reason", REASON),
                            String.class);

            assertThat(refused.getStatusCode().value()).as(refused.getBody()).isEqualTo(422);
            assertThat(code(refused)).isEqualTo("plan-item-not-in-target");
            assertThat(refused.getBody()).contains("optionalNote");
            assertThat(caseDefinitionOf(cmmn7, open)).isEqualTo(from);
            assertThat(openTasks(cmmn7, open, "optionalNote")).isEqualTo(1);
            assertThat(get("/api/case-definitions/engine-7/" + from + "/migration-batches"))
                    .isEmpty();
        } finally {
            Files.deleteIfExists(singleQuoted);
        }
    }

    /**
     * A failed batch part. The BFF only ever sends {@code {toCaseDefinitionId}}, which the engine
     * applies; a part that fails needs a document the engine cannot apply, so this one batch goes
     * to the engine directly. Measured on 7.1.0: the part's {@code status} is {@code fail}.
     */
    @Test
    void sevenOneCountsAFailedBatchPartUnderTheEnginesStatusAndNamesTheCase() throws Exception {
        String from = deploy(cmmn7, deployments7, V1);
        String to = deploy(cmmn7, deployments7, V1);
        String stuck = startCase(cmmn7, from, "ISSUE500-seven-failed-part");
        cmmn7.post()
                .uri("/cmmn-repository/case-definitions/" + from + "/batch-migrate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "toCaseDefinitionId",
                        to,
                        "activatePlanItemDefinitions",
                        List.of(Map.of("planItemDefinitionId", "issue500NoSuchPlanItem"))))
                .retrieve()
                .toBodilessEntity();

        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    JsonNode batches = get("/api/case-definitions/engine-7/" + from + "/migration-batches");
                    assertThat(batches).hasSize(1);
                    assertThat(batches.get(0).path("parts").asInt()).isEqualTo(1);
                    assertThat(batches.get(0).path("partsByStatus").path("fail").asInt())
                            .as(batches.toString())
                            .isEqualTo(1);
                    assertThat(ids(batches.get(0).path("notSucceededCaseIds"))).containsExactly(stuck);
                });
        assertThat(caseDefinitionOf(cmmn7, stuck)).isEqualTo(from);
    }

    /** The prod twin is the same 7.1 engine: each of the three doors needs its typed confirmation. */
    @Test
    void sevenOneProdTwinNeedsTheTypedCountOrCaseIdOnAllThreeDoors() throws Exception {
        String from = deploy(cmmn7, deployments7, V1);
        String to = deploy(cmmn7, deployments7, V1); // the same model: nothing a batch could lose
        List<String> running = List.of(
                startCase(cmmn7, from, "ISSUE500-prod-running-0"), startCase(cmmn7, from, "ISSUE500-prod-running-1"));
        List<String> ended = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = startCase(cmmn7, from, "ISSUE500-prod-ended-" + i);
            for (String task : List.of("reviewTask", "extraCheck", "optionalNote")) {
                completeTask(cmmn7, id, task);
            }
            ended.add(id);
        }

        // -- by-definition batch: the typed count of running cases --
        String batchDoor = "/api/case-definitions/engine-7-prod/" + from + "/migrate-batch";
        ResponseEntity<String> noCount = as("admin").postForEntity(batchDoor, prodBody(to, null, null), String.class);
        assertThat(noCount.getStatusCode()).as(noCount.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(noCount)).isEqualTo("confirm-count-required");
        assertThat(noCount.getBody()).contains("running cases (2)");
        ResponseEntity<String> wrongCount = as("admin").postForEntity(batchDoor, prodBody(to, 3, null), String.class);
        assertThat(wrongCount.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(wrongCount)).isEqualTo("bulk-count-drift");
        assertThat(get("/api/case-definitions/engine-7-prod/" + from + "/migration-batches"))
                .isEmpty();
        assertThat(caseDefinitionOf(cmmn7, running.get(0))).isEqualTo(from);

        ResponseEntity<String> batch = as("admin").postForEntity(batchDoor, prodBody(to, 2, null), String.class);
        assertThat(batch.getStatusCode()).as(batch.getBody()).isEqualTo(HttpStatus.OK);
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(caseDefinitionOf(cmmn7, running.get(0))).isEqualTo(to);
                    assertThat(caseDefinitionOf(cmmn7, running.get(1))).isEqualTo(to);
                });
        assertThat(auditPayload("engine-7-prod", null, CaseDefinitionMigrationService.BATCH_ACTION)
                        .path("runningCases")
                        .asInt())
                .isEqualTo(2);

        // -- one ended case: the typed case id --
        String caseDoor = "/api/cases/engine-7-prod/" + ended.get(0) + "/migrate-historic";
        for (String token : new String[] {null, ended.get(1)}) {
            ResponseEntity<String> refused =
                    as("admin").postForEntity(caseDoor, prodBody(to, null, token), String.class);
            assertThat(refused.getStatusCode()).as(refused.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(code(refused)).isEqualTo("confirm-token-mismatch");
        }
        assertThat(historicDefinitionOf(cmmn7, ended.get(0))).isEqualTo(from);
        ResponseEntity<String> one =
                as("admin").postForEntity(caseDoor, prodBody(to, null, ended.get(0)), String.class);
        assertThat(one.getStatusCode()).as(one.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(historicDefinitionOf(cmmn7, ended.get(0))).isEqualTo(to);

        // -- by-definition historic: the typed count of the ended cases still on the source --
        String historicDoor = "/api/case-definitions/engine-7-prod/" + from + "/migrate-historic";
        ResponseEntity<String> noEnded =
                as("admin").postForEntity(historicDoor, prodBody(to, null, null), String.class);
        assertThat(noEnded.getStatusCode()).as(noEnded.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(noEnded)).isEqualTo("confirm-count-required");
        assertThat(noEnded.getBody()).contains("ended cases (2)");
        ResponseEntity<String> staleCount =
                as("admin").postForEntity(historicDoor, prodBody(to, 3, null), String.class);
        assertThat(staleCount.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(staleCount)).isEqualTo("bulk-count-drift");
        assertThat(historicDefinitionOf(cmmn7, ended.get(1))).isEqualTo(from);

        ResponseEntity<String> rest = as("admin").postForEntity(historicDoor, prodBody(to, 2, null), String.class);
        assertThat(rest.getStatusCode()).as(rest.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(historicDefinitionOf(cmmn7, ended.get(1))).isEqualTo(to);
        assertThat(historicDefinitionOf(cmmn7, ended.get(2))).isEqualTo(to);
    }

    /* ------------------------------- BFF helpers ------------------------------- */

    private static Map<String, Object> prodBody(String to, Integer confirmedCount, String confirmToken) {
        Map<String, Object> body = new HashMap<>();
        body.put("toCaseDefinitionId", to);
        body.put("reason", REASON);
        body.put("ticketId", "INC-500");
        body.put("confirmedCount", confirmedCount);
        body.put("confirmToken", confirmToken);
        return body;
    }

    private static Map<String, Object> target(String caseId, String analysisId) {
        return Map.of("engineId", "engine-a", "instanceId", caseId, "jobId", analysisId);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> response = as("admin").getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as(path + " " + response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private boolean cmmnCapable(String engineId) throws Exception {
        for (JsonNode e : mapper.readTree(
                as("viewer").getForEntity("/api/engines", String.class).getBody())) {
            if (engineId.equals(e.path("id").asText())) {
                return e.path("capabilities").path("scopeType").asBoolean(false);
            }
        }
        return false;
    }

    private ResponseEntity<String> migrateCase(String engineId, String caseId, String to, String confirmToken) {
        Map<String, Object> body = new HashMap<>();
        body.put("toCaseDefinitionId", to);
        body.put("reason", REASON);
        body.put("confirmToken", confirmToken);
        return as("admin")
                .postForEntity("/api/cases/" + engineId + "/" + caseId + "/actions/migrate-case", body, String.class);
    }

    private static Map<String, Object> filter(String from, String to) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("fromCaseDefinitionId", from);
        filter.put("toCaseDefinitionId", to);
        return filter;
    }

    private JsonNode analyze(String engineId, String from, String to) throws Exception {
        return analyze(engineId, filter(from, to));
    }

    private JsonNode analyze(String engineId, Map<String, Object> filter) throws Exception {
        ResponseEntity<String> started =
                as("admin").postForEntity("/api/case-migration/analyses?engineId=" + engineId, filter, String.class);
        assertThat(started.getStatusCode()).as(started.getBody()).isEqualTo(HttpStatus.OK);
        String id = mapper.readTree(started.getBody()).path("id").asText();
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(250, TimeUnit.MILLISECONDS)
                .until(() -> !"RUNNING"
                        .equals(get("/api/case-migration/analyses/" + id)
                                .path("status")
                                .asText()));
        JsonNode done = get("/api/case-migration/analyses/" + id);
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("COMPLETED");
        assertThat(done.path("lowerBound").asBoolean(true)).isFalse();
        return done;
    }

    private JsonNode awaitTerminal(String jobId) throws Exception {
        await().atMost(120, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    String state = get("/api/bulk/" + jobId).path("state").asText();
                    return !"PENDING".equals(state) && !"RUNNING".equals(state);
                });
        return get("/api/bulk/" + jobId);
    }

    private String code(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).path("code").asText();
    }

    /** The newest audit row of this action (for {@code instanceId} when given), which must have ended ok. */
    private JsonNode auditPayload(String engineId, String instanceId, String action) throws Exception {
        List<AuditEntry> rows = instanceId != null
                ? audit.findByEngineIdAndInstanceIdOrderByTsDesc(engineId, instanceId, PageRequest.of(0, 10))
                : audit.findAll();
        AuditEntry row = rows.stream()
                .filter(e -> action.equals(e.getAction()) && engineId.equals(e.getEngineId()))
                .max(java.util.Comparator.comparing(AuditEntry::getTs))
                .orElseThrow(() -> new AssertionError("no '" + action + "' audit row on " + engineId));
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.ok);
        return mapper.readTree(row.getPayload());
    }

    private static JsonNode versionRow(JsonNode versions, String caseDefinitionId) {
        for (JsonNode row : versions.path("versions")) {
            if (caseDefinitionId.equals(row.path("caseDefinitionId").asText())) {
                return row;
            }
        }
        throw new AssertionError("no version row for " + caseDefinitionId);
    }

    private static JsonNode item(JsonNode job, String caseId) {
        for (JsonNode item : job.path("items")) {
            if (caseId.equals(item.path("instanceId").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item for " + caseId);
    }

    private static List<String> instanceIds(JsonNode job) {
        List<String> ids = new ArrayList<>();
        job.path("items").forEach(item -> ids.add(item.path("instanceId").asText()));
        return ids;
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.asText()));
        return ids;
    }

    private static List<String> concat(List<String> list, String... more) {
        List<String> all = new ArrayList<>(list);
        all.addAll(List.of(more));
        return all;
    }

    /* ------------------------------- engine seeding (cmmn REST only) ------------------------------- */

    private static String url(String portEnv, String port) {
        return "http://localhost:" + System.getenv().getOrDefault(portEnv, port) + "/flowable-rest/service";
    }

    /** Deploys the file as a NEW version of the case key and returns that version's definition id. */
    @SuppressWarnings("unchecked")
    private static String deploy(RestClient cmmn, List<String> deployments, Path file) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new FileSystemResource(file));
        Map<String, Object> deployment = cmmn.post()
                .uri("/cmmn-repository/deployments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(Map.class);
        String deploymentId = String.valueOf(deployment.get("id"));
        deployments.add(deploymentId);
        Map<String, Object> page = cmmn.get()
                .uri("/cmmn-repository/case-definitions?deploymentId=" + deploymentId)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        assertThat(data).as("case definition of deployment " + deploymentId).hasSize(1);
        return String.valueOf(data.get(0).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static String startCase(RestClient cmmn, String caseDefinitionId, String businessKey) {
        Map<String, Object> started = cmmn.post()
                .uri("/cmmn-runtime/case-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionId", caseDefinitionId, "businessKey", businessKey))
                .retrieve()
                .body(Map.class);
        return String.valueOf(started.get("id"));
    }

    @SuppressWarnings("unchecked")
    private static void completeTask(RestClient cmmn, String caseId, String taskDefinitionKey) {
        Map<String, Object> page = cmmn.get()
                .uri("/cmmn-runtime/tasks?caseInstanceId=" + caseId + "&taskDefinitionKey=" + taskDefinitionKey)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        assertThat(data).as(taskDefinitionKey + " of case " + caseId).hasSize(1);
        cmmn.post()
                .uri("/cmmn-runtime/tasks/" + data.get(0).get("id"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", "complete"))
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private static int openTasks(RestClient cmmn, String caseId, String taskDefinitionKey) {
        Map<String, Object> page = cmmn.get()
                .uri("/cmmn-runtime/tasks?caseInstanceId=" + caseId + "&taskDefinitionKey=" + taskDefinitionKey)
                .retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) page.get("data")).size();
    }

    /** Out-of-band migrate, bypassing the BFF. */
    private static void engineMigrate(RestClient cmmn, String caseId, String to) {
        cmmn.post()
                .uri("/cmmn-runtime/case-instances/" + caseId + "/migrate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("toCaseDefinitionId", to))
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private static String caseDefinitionOf(RestClient cmmn, String caseId) {
        Map<String, Object> row = cmmn.get()
                .uri("/cmmn-runtime/case-instances/" + caseId)
                .retrieve()
                .body(Map.class);
        return String.valueOf(row.get("caseDefinitionId"));
    }

    @SuppressWarnings("unchecked")
    private static String historicDefinitionOf(RestClient cmmn, String caseId) {
        Map<String, Object> row = cmmn.get()
                .uri("/cmmn-history/historic-case-instances/" + caseId)
                .retrieve()
                .body(Map.class);
        return String.valueOf(row.get("caseDefinitionId"));
    }

    /** Removes the engine batch records whose source is a definition of this deployment. */
    @SuppressWarnings("unchecked")
    private static void deleteBatchesQuietly(RestClient process, RestClient cmmn, String deploymentId) {
        try {
            Map<String, Object> definitions = cmmn.get()
                    .uri("/cmmn-repository/case-definitions?deploymentId=" + deploymentId)
                    .retrieve()
                    .body(Map.class);
            for (Map<String, Object> definition : (List<Map<String, Object>>) definitions.get("data")) {
                Map<String, Object> batches = process.get()
                        .uri("/management/batches?batchType=caseMigration&size=100&searchKey=" + definition.get("id"))
                        .retrieve()
                        .body(Map.class);
                for (Map<String, Object> batch : (List<Map<String, Object>>) batches.get("data")) {
                    process.delete()
                            .uri("/management/batches/" + batch.get("id"))
                            .retrieve()
                            .toBodilessEntity();
                }
            }
        } catch (RuntimeException e) {
            // residue cleanup must never fail a test
        }
    }

    private static void deleteDeploymentQuietly(RestClient cmmn, String deploymentId) {
        try {
            cmmn.delete()
                    .uri("/cmmn-repository/deployments/" + deploymentId + "?cascade=true")
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // residue cleanup must never fail a test
        }
    }
}
