package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the INSTANCE-MIGRATION.md §14 typed-findings taxonomy against REAL
 * flowable-rest. Deploys the four §14.2/§14.9 probe topologies over REST (never a single
 * {@code ACT_*} write) and proves, on the live engine:
 *
 * <ul>
 *   <li>the two <b>calibrated false blockers are gone</b> — a removed active SCOPE ([M3]) and a
 *       removed BOUNDARY subscription ([M4]) now preview as executable WARNINGS and migrate
 *       successfully THROUGH THE BFF, which was impossible before (422 before any engine contact);
 *   <li>{@code TYPE_CHANGED_SAME_ID} is loud and its calibrated consequence is real — the new
 *       synchronous behavior runs during the migrate call itself ([M6]);
 *   <li>{@code BOUNDARY_CLOCK_RESET} is raised for an UNCHANGED timer, with the due date
 *       <b>recorded, never asserted</b> — [M5] is version-divergent (moved on 6.8, preserved on
 *       7.1) and a universal assert would enshrine one engine's behavior;
 *   <li>the MI-root blocker is <b>retained</b> (no uncalibrated downgrade) and execute refuses 422;
 *   <li><b>the rails are estimate-independent</b> — green, warning-carrying and blocked estimates
 *       hit byte-identical RBAC / CAS / reason / typed-confirm refusals;
 *   <li>the execute audit row is {@code migrate-instance/v2} with typed {@code bffFindings} and no
 *       {@code bffWarnings}.
 * </ul>
 *
 * <p>Local-only (not in ci.yml itClass, like {@code MigrationIT}). Requires:
 * docker compose -f docker/docker-compose.dev.yml up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationFindingsIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";

    private static final Path PROBE_A_V1 = Path.of("..", "docker", "processes", "tax-probe-a-v1.bpmn20.xml");
    private static final Path PROBE_A_V2 = Path.of("..", "docker", "processes", "tax-probe-a-v2.bpmn20.xml");
    private static final Path PROBE_B_V1 = Path.of("..", "docker", "processes", "tax-probe-b-v1.bpmn20.xml");
    private static final Path PROBE_B_V2 = Path.of("..", "docker", "processes", "tax-probe-b-v2.bpmn20.xml");
    private static final Path PROBE_C_V1 = Path.of("..", "docker", "processes", "tax-probe-c-v1.bpmn20.xml");
    private static final Path PROBE_C_V2 = Path.of("..", "docker", "processes", "tax-probe-c-v2.bpmn20.xml");
    private static final Path PROBE_D_V1 = Path.of("..", "docker", "processes", "tax-probe-d-v1.bpmn20.xml");
    private static final Path PROBE_D_V2 = Path.of("..", "docker", "processes", "tax-probe-d-v2.bpmn20.xml");

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

    private RestClient engine;

    /** probe A: an active subprocess scope that disappears in the target ([M3]). */
    private String scopeFrom;

    private String scopeTo;
    /** probe B: same id, userTask → synchronous serviceTask ([M6]); plus an IDENTICAL redeploy. */
    private String typeFrom;

    private String typeSame;
    private String typeTo;
    /** probe C: an interrupting boundary timer, removed in one target and identical in another. */
    private String boundaryFrom;

    private String boundaryRemoved;
    private String boundaryIdentical;
    /** probe D: a multi-instance ROOT that disappears in the target (blocker RETAINED). */
    private String miFrom;

    private String miTo;

    @BeforeAll
    void seedProbeTopologies() {
        engine = EngineSeed.requireReachable(ENGINE, "");

        scopeFrom = definitionIdForVersion("taxProbeA", EngineSeed.deployNewVersion(engine, "taxProbeA", PROBE_A_V1));
        scopeTo = definitionIdForVersion("taxProbeA", EngineSeed.deployNewVersion(engine, "taxProbeA", PROBE_A_V2));

        typeFrom = definitionIdForVersion("taxProbeB", EngineSeed.deployNewVersion(engine, "taxProbeB", PROBE_B_V1));
        // A byte-identical redeploy: the GREEN (zero-finding) estimate for the rails test.
        typeSame = definitionIdForVersion("taxProbeB", EngineSeed.deployNewVersion(engine, "taxProbeB", PROBE_B_V1));
        typeTo = definitionIdForVersion("taxProbeB", EngineSeed.deployNewVersion(engine, "taxProbeB", PROBE_B_V2));

        boundaryFrom =
                definitionIdForVersion("taxProbeC", EngineSeed.deployNewVersion(engine, "taxProbeC", PROBE_C_V1));
        boundaryRemoved =
                definitionIdForVersion("taxProbeC", EngineSeed.deployNewVersion(engine, "taxProbeC", PROBE_C_V2));
        // §14.2 taxProbeC v3: the SAME boundary timer again — the clock-reset probe.
        boundaryIdentical =
                definitionIdForVersion("taxProbeC", EngineSeed.deployNewVersion(engine, "taxProbeC", PROBE_C_V1));

        miFrom = definitionIdForVersion("taxProbeD", EngineSeed.deployNewVersion(engine, "taxProbeD", PROBE_D_V1));
        miTo = definitionIdForVersion("taxProbeD", EngineSeed.deployNewVersion(engine, "taxProbeD", PROBE_D_V2));

        // Fail-closed until the first health probe answers — wait for the dev id AND the prod twin.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> migrationCapable("engine-a") && migrationCapable("engine-a-prod"));
    }

    /* ============================ downgrade 1 — the removed SCOPE ============================ */

    @Test
    void removedActiveScopeIsNowAnExecutableWarning_andTheMigrationLandsThroughTheBff() throws Exception {
        String instanceId = startOn(scopeFrom);
        assertThat(activeExecutionActivityIds(instanceId)).containsExactlyInAnyOrder("scopeA", "stepA");

        JsonNode preview = previewOk(instanceId, scopeTo);

        // [M3]: the engine accepts this with an EMPTY mapping list on 6.8.0 and 7.1.0, so the BFF
        // must not refuse it. Before §14 this preview was executable:false and execute 422'd.
        assertThat(preview.path("executable").asBoolean(false)).isTrue();
        assertThat(preview.path("restBody").path("activityMappings")).isEmpty();

        JsonNode scope = activityEntry(preview, "scopeA");
        assertThat(scope.path("blocker").asBoolean(true)).isFalse();
        assertThat(scope.path("warning").asBoolean(false)).isTrue();
        assertThat(scope.path("status").asText()).isEqualTo("SCOPE_REMOVED");
        assertThat(findingCodes(scope)).containsExactly("ACTIVE_SCOPE_REMOVED");
        assertThat(scope.path("findings").get(0).path("severity").asText()).isEqualTo("WARNING");

        JsonNode leaf = activityEntry(preview, "stepA");
        assertThat(leaf.path("blocker").asBoolean(true)).isFalse();
        assertThat(findingCodes(leaf)).containsExactly("ACTIVE_IN_REMOVED_SCOPE");

        // …and it genuinely migrates through the full tier-3 rails.
        ResponseEntity<String> response =
                execute("engine-a", instanceId, boundBody(preview, scopeTo, "removed the scope in a bad deploy"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(currentDefinitionId(instanceId)).isEqualTo(scopeTo);
        // The scope execution is gone; the token re-homed to the process root ([M3]).
        assertThat(activeExecutionActivityIds(instanceId)).containsExactly("stepA");
    }

    /* =========================== downgrade 2 — the removed BOUNDARY =========================== */

    @Test
    void removedBoundarySubscriptionIsNowAnExecutableWarning_andTheMigrationLandsThroughTheBff() throws Exception {
        String instanceId = startOn(boundaryFrom);
        assertThat(activeExecutionActivityIds(instanceId)).containsExactlyInAnyOrder("stepC", "bndC");
        assertThat(timerJobDueDates(instanceId)).hasSize(1);

        JsonNode preview = previewOk(instanceId, boundaryRemoved);

        assertThat(preview.path("executable").asBoolean(false)).isTrue();
        JsonNode boundary = activityEntry(preview, "bndC");
        assertThat(boundary.path("blocker").asBoolean(true)).isFalse();
        assertThat(boundary.path("warning").asBoolean(false)).isTrue();
        assertThat(boundary.path("status").asText()).isEqualTo("BOUNDARY_REMOVED");
        assertThat(findingCodes(boundary)).containsExactly("BOUNDARY_SUBSCRIPTION_REMOVED");
        assertThat(activityEntry(preview, "stepC").path("status").asText()).isEqualTo("AUTO_MAPPED");
        // The instance-level INFO rides along whenever any boundary execution is live.
        assertThat(instanceFindingCodes(preview)).contains("BOUNDARY_CLOCK_RESET");

        ResponseEntity<String> response = execute(
                "engine-a", instanceId, boundBody(preview, boundaryRemoved, "drop the obsolete deadline branch"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(currentDefinitionId(instanceId)).isEqualTo(boundaryRemoved);
        // [M4]: the subscription AND its timer job vanish with no error anywhere — exactly the
        // silent loss the WARNING exists to announce.
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(activeExecutionActivityIds(instanceId)).containsExactly("stepC");
                    assertThat(timerJobDueDates(instanceId)).isEmpty();
                });
    }

    /* ========================= the version-divergent clock INFO ([M5]) ========================= */

    @Test
    void unchangedBoundaryTimerStillRaisesTheClockResetInfo_dueDateRecordedNotAsserted() throws Exception {
        String instanceId = startOn(boundaryFrom);
        List<String> before = timerJobDueDates(instanceId);
        assertThat(before).hasSize(1);

        JsonNode preview = previewOk(instanceId, boundaryIdentical);

        // Nothing about the model changed, so no activity carries a finding…
        assertThat(preview.path("executable").asBoolean(false)).isTrue();
        assertThat(activityEntry(preview, "stepC").path("status").asText()).isEqualTo("AUTO_MAPPED");
        assertThat(activityEntry(preview, "bndC").path("status").asText()).isEqualTo("AUTO_MAPPED");
        assertThat(preview.path("activities"))
                .allSatisfy(a -> assertThat(a.path("findings")).isEmpty());
        // …but the re-subscription happens anyway, so the instance-level INFO is raised.
        assertThat(instanceFindingCodes(preview)).containsExactly("BOUNDARY_CLOCK_RESET");
        JsonNode info = preview.path("findings").get(0);
        assertThat(info.path("severity").asText()).isEqualTo("INFO");
        assertThat(info.path("activityId").isNull()).isTrue();
        assertThat(info.path("detail").asText()).contains("MAY restart");

        ResponseEntity<String> response =
                execute("engine-a", instanceId, boundBody(preview, boundaryIdentical, "re-pin to the rebuilt version"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The timer is RE-SUBSCRIBED — that much is universal. Whether its due date moved is NOT:
        // 6.8.0 restarted the clock at migrate time, 7.1.0 preserved the original ([M5]). Asserting
        // either would enshrine one engine's behavior, so the observation is recorded in the
        // assertion description and only the version-neutral invariant is asserted.
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    List<String> after = timerJobDueDates(instanceId);
                    assertThat(after)
                            .as(
                                    "boundary timer due date before=%s after=%s (RECORDED, not asserted — [M5] is "
                                            + "version-divergent: reset on 6.8.0, preserved on 7.1.0)",
                                    before, after)
                            .hasSize(1);
                });
        assertThat(currentDefinitionId(instanceId)).isEqualTo(boundaryIdentical);
    }

    /* ================== the loud class: same id, different type ([M6]) ================== */

    @Test
    void sameIdTypeChangeWarnsLoudly_andTheNewBehaviorRunsDuringTheMigrateCall() throws Exception {
        String instanceId = startOn(typeFrom);
        assertThat(activeExecutionActivityIds(instanceId)).containsExactly("stepT");

        JsonNode preview = previewOk(instanceId, typeTo);
        assertThat(preview.path("executable").asBoolean(false)).isTrue();
        JsonNode step = activityEntry(preview, "stepT");
        assertThat(step.path("status").asText()).isEqualTo("TYPE_CHANGED");
        assertThat(step.path("warning").asBoolean(false)).isTrue();
        assertThat(findingCodes(step)).containsExactly("TYPE_CHANGED_SAME_ID");
        assertThat(step.path("findings").get(0).path("detail").asText())
                .contains("execute IMMEDIATELY as part of the migrate call");

        ResponseEntity<String> response =
                execute("engine-a", instanceId, boundBody(preview, typeTo, "swap the manual step for automation"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // [M6]: the synchronous serviceTask ran AS PART OF the migrate call — the instance is
        // already finished by the time the BFF re-reads it.
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(historicEndTime(instanceId)).isNotNull();
                });
    }

    /* ===================== MI-root retention — the deliberate NON-downgrade ===================== */

    @Test
    void multiInstanceRootWithNoTargetKeepsTheBlockerAndExecuteRefuses422() throws Exception {
        String instanceId = startOn(miFrom);
        assertThat(activeExecutionActivityIds(instanceId)).containsExactlyInAnyOrder("miScope", "stepM");

        JsonNode preview = previewOk(instanceId, miTo);
        assertThat(preview.path("executable").asBoolean(true)).isFalse();
        JsonNode miScope = activityEntry(preview, "miScope");
        assertThat(miScope.path("status").asText()).isEqualTo("FLAGGED_UNMAPPED");
        assertThat(miScope.path("blocker").asBoolean(false)).isTrue();
        assertThat(findingCodes(miScope)).containsExactly("UNMAPPED_ACTIVE_ACTIVITY");
        assertThat(miScope.path("findings").get(0).path("severity").asText()).isEqualTo("BLOCKER_ADVICE");

        ResponseEntity<String> response =
                execute("engine-a", instanceId, boundBody(preview, miTo, "attempt the MI scope removal"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(response.getBody()).path("code").asText()).isEqualTo("unmapped-activities");
        assertThat(currentDefinitionId(instanceId)).isEqualTo(miFrom); // nothing happened
    }

    /**
     * §14.9's designated evidence-gatherer. The MI-root blocker is retained because NO multi-
     * instance case was calibrated when §14 was locked — not because the engine was proven to
     * reject it. This probe fires the migrate ENGINE-DIRECT (out of band, bypassing the BFF —
     * never a table write) on a throwaway instance and RECORDS what the engine actually does, so
     * a future {@code taxonomyVersion} bump can be argued from evidence instead of speculation.
     * It deliberately asserts no verdict about the engine: the only assertion is that the BFF's
     * retention is <b>deliberate</b>, i.e. unchanged by whatever the engine tolerates.
     */
    @Test
    void engineDirectProbeRecordsTheMultiInstanceOutcome_andTheBffRetentionIsDeliberate() throws Exception {
        String probeInstance = startOn(miFrom);
        int status;
        String body;
        try {
            ResponseEntity<String> direct = engineDirectMigrate(probeInstance, miTo);
            status = direct.getStatusCode().value();
            body = String.valueOf(direct.getBody());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            status = e.getStatusCode().value();
            body = e.getResponseBodyAsString();
        }
        List<String> afterIds = activeExecutionActivityIds(probeInstance);
        String observation = "engine-direct migrate of an UNMAPPED multi-instance root -> HTTP " + status
                + ", activity ids now " + afterIds + ", body " + body;

        // A fresh instance of the same shape: the BFF still blocks, whatever the engine tolerates.
        String instanceId = startOn(miFrom);
        JsonNode preview = previewOk(instanceId, miTo);
        assertThat(preview.path("executable").asBoolean(true))
                .as(
                        "MI-root retention is deliberate (§14.3): a downgrade needs a design change and a"
                                + " taxonomyVersion bump, never an ad-hoc reaction to one engine. RECORDED: %s",
                        observation)
                .isFalse();
    }

    /* ==================== the rails are INDEPENDENT of the estimate (§14.6) ==================== */

    /**
     * The issue's explicitly named test. A green estimate (zero findings), a warning-carrying one
     * and a blocked one must hit BYTE-IDENTICAL rails: ADMIN floor, mandatory CAS binding, reason
     * ≥10 chars, and the prod typed-confirm. No finding — green, amber or red — shortcuts or adds
     * a single guard, and nothing moves in any refusal.
     */
    @Test
    void everyRailRefusesIdenticallyForGreen_warning_andBlockedEstimates() throws Exception {
        record Estimate(String label, String fromDefinitionId, String toDefinitionId) {}
        List<Estimate> estimates = List.of(
                new Estimate("green (zero findings)", typeFrom, typeSame),
                new Estimate("warning-carrying", scopeFrom, scopeTo),
                new Estimate("blocked", miFrom, miTo));

        for (Estimate estimate : estimates) {
            String instanceId = startOn(estimate.fromDefinitionId());
            JsonNode preview = previewOk(instanceId, estimate.toDefinitionId());
            String label = estimate.label();

            // Rail 1 — ADMIN floor, unconditional every environment.
            ResponseEntity<String> asOperator = as("operator")
                    .postForEntity(
                            "/api/instances/engine-a/" + instanceId + "/migrate/execute",
                            boundBody(preview, estimate.toDefinitionId(), "operator tries the tier-3 verb"),
                            String.class);
            assertThat(asOperator.getStatusCode()).as("ADMIN floor — %s", label).isEqualTo(HttpStatus.FORBIDDEN);

            // Rail 2 — the mandatory compare-and-set binding (no blind execute).
            Map<String, Object> unbound = new java.util.HashMap<>();
            unbound.put("toDefinitionId", estimate.toDefinitionId());
            unbound.put("reason", "execute with no preview binding at all");
            assertThat(code(execute("engine-a", instanceId, unbound)))
                    .as("CAS required — %s", label)
                    .isEqualTo("preview-required");

            // Rail 3 — reason discipline (≥10 chars), checked before executability.
            Map<String, Object> shortReason = boundBody(preview, estimate.toDefinitionId(), "too short");
            assertThat(code(execute("engine-a", instanceId, shortReason)))
                    .as("reason ≥10 — %s", label)
                    .isEqualTo("reason-too-short");

            // Rail 4 — CAS divergence is a 409 regardless of what the estimate said.
            Map<String, Object> staleDigest = boundBody(preview, estimate.toDefinitionId(), "stale approval replay");
            staleDigest.put(
                    "expectedActivityStateDigest", "0000000000000000000000000000000000000000000000000000000000000000");
            assertThat(code(execute("engine-a", instanceId, staleDigest)))
                    .as("CAS divergence — %s", label)
                    .isEqualTo("instance-moved-since-preview");

            // Rail 5 — the PROD typed-confirm escalation on the prod twin of the same engine.
            Map<String, Object> noToken = boundBody(preview, estimate.toDefinitionId(), "prod migrate rehearsal");
            assertThat(code(execute("engine-a-prod", instanceId, noToken)))
                    .as("prod typed confirm — %s", label)
                    .isEqualTo("confirm-token-mismatch");

            // Nothing moved through any of it.
            assertThat(currentDefinitionId(instanceId))
                    .as("no rail refusal moved the instance — %s", label)
                    .isEqualTo(estimate.fromDefinitionId());
        }
    }

    /* ============================== the migrate-instance/v2 audit ============================== */

    @Test
    void executeAuditsMigrateInstanceV2WithTypedFindingsAndNoBffWarnings() throws Exception {
        String instanceId = startOn(scopeFrom);
        JsonNode preview = previewOk(instanceId, scopeTo);
        assertThat(execute("engine-a", instanceId, boundBody(preview, scopeTo, "audit the typed findings payload"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    JsonNode rows = mapper.readTree(as("admin")
                            .getForEntity("/api/instances/engine-a/" + instanceId + "/audit", String.class)
                            .getBody());
                    assertThat(rows).isNotEmpty();
                    JsonNode row = rows.get(0);
                    assertThat(row.path("action").asText()).isEqualTo("migrate-instance");
                    assertThat(row.path("outcome").asText()).isEqualTo("ok");

                    JsonNode payload = mapper.readTree(row.path("payload").asText());
                    assertThat(payload.path("schema").asText()).isEqualTo("migrate-instance/v2");
                    assertThat(payload.path("taxonomyVersion").asInt()).isEqualTo(MigrationFinding.TAXONOMY_VERSION);
                    // engineValidated is the constant honesty marker — no finding ever implies otherwise.
                    assertThat(payload.path("engineValidated").asBoolean(true)).isFalse();
                    // v1's ad-hoc string list is GONE (replaced, not duplicated — keeping both invites drift).
                    assertThat(payload.has("bffWarnings")).isFalse();

                    List<String> codes = payload.path("bffFindings").findValuesAsText("code");
                    assertThat(codes).contains("ACTIVE_SCOPE_REMOVED", "ACTIVE_IN_REMOVED_SCOPE");
                    // A BLOCKER_ADVICE can never reach a successful execute row — execute 422s first.
                    assertThat(payload.path("bffFindings").findValuesAsText("severity"))
                            .doesNotContain("BLOCKER_ADVICE");
                    assertThat(payload.path("bffFindings").get(0).has("activityId"))
                            .isTrue();
                    assertThat(payload.path("bffFindings").get(0).path("detail").asText())
                            .isNotBlank();
                });
    }

    /* ------------------------------------- plumbing ------------------------------------- */

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private boolean migrationCapable(String engineId) throws Exception {
        JsonNode engines = mapper.readTree(
                as("viewer").getForEntity("/api/engines", String.class).getBody());
        for (JsonNode e : engines) {
            if (engineId.equals(e.path("id").asText())) {
                return e.path("capabilities").path("migration").asBoolean(false);
            }
        }
        return false;
    }

    private JsonNode previewOk(String instanceId, String toDefinitionId) throws Exception {
        ResponseEntity<String> response = as("admin")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/migrate/preview",
                        Map.of("toDefinitionId", toDefinitionId),
                        String.class);
        assertThat(response.getStatusCode())
                .as("preview body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> execute(String engineId, String instanceId, Map<String, Object> body) {
        return as("admin")
                .postForEntity(
                        "/api/instances/" + engineId + "/" + instanceId + "/migrate/execute", body, String.class);
    }

    private String code(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).path("code").asText();
    }

    /** The execute body carrying the mandatory CAS binding from a fresh preview. */
    private Map<String, Object> boundBody(JsonNode preview, String toDefinitionId, String reason) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("toDefinitionId", toDefinitionId);
        body.put("reason", reason);
        body.put("expectedFromDefinitionId", preview.path("fromDefinitionId").asText());
        body.put(
                "expectedActivityStateDigest",
                preview.path("activityStateDigest").asText());
        return body;
    }

    private static List<String> findingCodes(JsonNode activityEntry) {
        return activityEntry.path("findings").findValuesAsText("code");
    }

    private static List<String> instanceFindingCodes(JsonNode preview) {
        return preview.path("findings").findValuesAsText("code");
    }

    private static JsonNode activityEntry(JsonNode preview, String fromActivityId) {
        for (JsonNode entry : preview.path("activities")) {
            if (fromActivityId.equals(entry.path("fromActivityId").asText())) {
                return entry;
            }
        }
        throw new AssertionError("no activity entry for '" + fromActivityId + "' in " + preview.path("activities"));
    }

    /* ---------------------------- direct engine reads / seeding ---------------------------- */

    @SuppressWarnings("unchecked")
    private String definitionIdForVersion(String key, int version) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + key + "&version=" + version)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return String.valueOf(data.get(0).get("id"));
    }

    @SuppressWarnings("unchecked")
    private String startOn(String definitionId) {
        Map<String, Object> started = engine.post()
                .uri("/runtime/process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionId", definitionId))
                .retrieve()
                .body(Map.class);
        return String.valueOf(started.get("id"));
    }

    @SuppressWarnings("unchecked")
    private String currentDefinitionId(String instanceId) {
        Map<String, Object> instance = engine.get()
                .uri("/runtime/process-instances/" + instanceId)
                .retrieve()
                .body(Map.class);
        return String.valueOf(instance.get("processDefinitionId"));
    }

    /** Active executions as the pre-check sees them ([M2]) — the instance root is filtered out. */
    @SuppressWarnings("unchecked")
    private List<String> activeExecutionActivityIds(String instanceId) {
        Map<String, Object> page = engine.get()
                .uri("/runtime/executions?processInstanceId=" + instanceId + "&size=200")
                .retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) page.get("data"))
                .stream()
                        .map(row -> row.get("activityId"))
                        .filter(java.util.Objects::nonNull)
                        .map(String::valueOf)
                        .distinct()
                        .sorted()
                        .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> timerJobDueDates(String instanceId) {
        Map<String, Object> page = engine.get()
                .uri("/management/timer-jobs?processInstanceId=" + instanceId)
                .retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) page.get("data"))
                .stream().map(row -> String.valueOf(row.get("dueDate"))).toList();
    }

    @SuppressWarnings("unchecked")
    private String historicEndTime(String instanceId) {
        Map<String, Object> instance = engine.get()
                .uri("/history/historic-process-instances/" + instanceId)
                .retrieve()
                .body(Map.class);
        Object endTime = instance.get("endTime");
        return endTime == null ? null : String.valueOf(endTime);
    }

    /**
     * Out-of-band engine mutation (guard-ladder E2E precedent): straight to flowable-rest,
     * bypassing the BFF. REST only — never a table write.
     */
    private ResponseEntity<String> engineDirectMigrate(String instanceId, String toDefinitionId) {
        return engine.post()
                .uri("/runtime/process-instances/" + instanceId + "/migrate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("toProcessDefinitionId", toDefinitionId, "activityMappings", List.of()))
                .retrieve()
                .toEntity(String.class);
    }
}
