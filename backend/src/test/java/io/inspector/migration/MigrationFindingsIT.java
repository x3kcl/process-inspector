package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * flowable-rest. Deploys the six §14.2/§14.9/§14.11 probe topologies over REST (never a single
 * {@code ACT_*} write) and proves, on the live engines:
 *
 * <ul>
 *   <li>the two <b>calibrated false blockers are gone</b> — a removed active SCOPE holding ONE
 *       token ([M3]) and a removed BOUNDARY subscription ([M4]) preview as executable WARNINGS,
 *       migrate successfully THROUGH THE BFF and remain continuable to completion;
 *   <li>the <b>lossy</b> sub-case of that same collapse is REFUSED before any engine contact
 *       (§14.11 [M10]) — a scope holding two or more concurrent tokens, and the id-preserving
 *       scope RENAME that is indistinguishable from it;
 *   <li>{@code TYPE_CHANGED_SAME_ID} is loud and its calibrated consequence is real — the new
 *       synchronous behavior runs during the migrate call itself ([M6]);
 *   <li>{@code BOUNDARY_CLOCK_RESET} is raised for an UNCHANGED timer, with the due date
 *       <b>recorded, never asserted</b> — [M5] is version-divergent (moved on 6.8, preserved on
 *       7.1) and a universal assert would enshrine one engine's behavior;
 *   <li>the MI-root blocker is <b>retained</b> (no uncalibrated downgrade) and execute refuses 422;
 *   <li><b>the rails are estimate-independent</b> — green, warning-carrying and BOTH kinds of
 *       blocked estimate hit byte-identical RBAC / CAS / reason / typed-confirm refusals;
 *   <li>the execute audit row is {@code migrate-instance/v2} with typed {@code bffFindings} and no
 *       {@code bffWarnings}.
 * </ul>
 *
 * <p><b>Both calibrated majors.</b> Every severity decision in §14 is justified by live behavior on
 * flowable-rest 6.8.0 AND 7.1.0, so the calibration-carrying tests are parametrised over
 * {@code {engine-a (6.8, :8081), engine-7 (7.1, :8083)}}. Testing only 6.8 would let a 7.x behavior
 * change silently invalidate a downgrade with a green suite.
 *
 * <p>CI: the {@code migration-findings} matrix leg in {@code .github/workflows/ci.yml} (#362)
 * boots {@code flowable-6}+{@code flowable-7}+postgres and runs this class. Locally:
 * {@code COMPOSE_PROFILES=flowable-6,flowable-7,postgres docker compose -f
 * docker/docker-compose.dev.yml up -d}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ENGINE_A_PASSWORD=test", "ENGINE_7_PASSWORD=test"})
@ActiveProfiles("it-findings")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationFindingsIT {

    private static final String ENGINE_A_URL =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";
    private static final String ENGINE_7_URL =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_7_PORT", "8083") + "/flowable-rest/service";

    /** The engines §14's severity decisions were calibrated on — both, always (7.1 coverage gap). */
    private static List<String> calibratedEngines() {
        return List.of("engine-a", "engine-7");
    }

    private static final Path PROBE_A_V1 = probe("tax-probe-a-v1.bpmn20.xml");
    private static final Path PROBE_A_V2 = probe("tax-probe-a-v2.bpmn20.xml");
    private static final Path PROBE_B_V1 = probe("tax-probe-b-v1.bpmn20.xml");
    private static final Path PROBE_B_V2 = probe("tax-probe-b-v2.bpmn20.xml");
    private static final Path PROBE_C_V1 = probe("tax-probe-c-v1.bpmn20.xml");
    private static final Path PROBE_C_V2 = probe("tax-probe-c-v2.bpmn20.xml");
    private static final Path PROBE_D_V1 = probe("tax-probe-d-v1.bpmn20.xml");
    private static final Path PROBE_D_V2 = probe("tax-probe-d-v2.bpmn20.xml");
    private static final Path PROBE_E_V1 = probe("tax-probe-e-v1.bpmn20.xml");
    private static final Path PROBE_E_V2 = probe("tax-probe-e-v2.bpmn20.xml");
    private static final Path PROBE_F_V1 = probe("tax-probe-f-v1.bpmn20.xml");
    private static final Path PROBE_F_V2 = probe("tax-probe-f-v2.bpmn20.xml");

    private static Path probe(String file) {
        return Path.of("..", "docker", "processes", file);
    }

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

    /**
     * One engine leg's probe deployments. Every field is a PINNED {@code processDefinitionId} on
     * that engine — definition ids are engine-local, so nothing here may be shared across legs.
     *
     * @param engine the direct flowable-rest client (out-of-band reads + the engine-direct probes)
     * @param scopeFrom probe A v1: ONE token inside a scope that v2 removes ([M3])
     * @param typeSame probe B redeployed byte-identical: the GREEN, zero-finding estimate
     * @param boundaryIdentical probe C v3: the same boundary timer again — the clock-reset probe
     * @param miFrom probe D: a multi-instance ROOT that v2 removes (blocker RETAINED)
     * @param lossyFrom probe E v1: a parallel fork INSIDE the scope v2 removes — TWO tokens ([M10])
     * @param renameFrom probe F v1: three tokens inside a scope v2 RENAMES rather than removes
     */
    private record Probes(
            RestClient engine,
            String scopeFrom,
            String scopeTo,
            String typeFrom,
            String typeSame,
            String typeTo,
            String boundaryFrom,
            String boundaryRemoved,
            String boundaryIdentical,
            String miFrom,
            String miTo,
            String lossyFrom,
            String lossyTo,
            String renameFrom,
            String renameTo) {}

    private final Map<String, Probes> legs = new LinkedHashMap<>();

    @BeforeAll
    void seedProbeTopologies() {
        legs.put("engine-a", deployProbes(EngineSeed.requireReachable(ENGINE_A_URL, "")));
        legs.put("engine-7", deployProbes(EngineSeed.requireReachable(ENGINE_7_URL, "--profile flowable-7")));

        // Fail-closed until the first health probe answers — wait for every registered id.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> migrationCapable("engine-a")
                        && migrationCapable("engine-a-prod")
                        && migrationCapable("engine-7"));
    }

    private Probes deployProbes(RestClient engine) {
        return new Probes(
                engine,
                deploy(engine, "taxProbeA", PROBE_A_V1),
                deploy(engine, "taxProbeA", PROBE_A_V2),
                deploy(engine, "taxProbeB", PROBE_B_V1),
                // A byte-identical redeploy: the GREEN (zero-finding) estimate for the rails test.
                deploy(engine, "taxProbeB", PROBE_B_V1),
                deploy(engine, "taxProbeB", PROBE_B_V2),
                deploy(engine, "taxProbeC", PROBE_C_V1),
                deploy(engine, "taxProbeC", PROBE_C_V2),
                // §14.2 taxProbeC v3: the SAME boundary timer again — the clock-reset probe.
                deploy(engine, "taxProbeC", PROBE_C_V1),
                deploy(engine, "taxProbeD", PROBE_D_V1),
                deploy(engine, "taxProbeD", PROBE_D_V2),
                deploy(engine, "taxProbeE", PROBE_E_V1),
                deploy(engine, "taxProbeE", PROBE_E_V2),
                deploy(engine, "taxProbeF", PROBE_F_V1),
                deploy(engine, "taxProbeF", PROBE_F_V2));
    }

    private String deploy(RestClient engine, String key, Path bpmn) {
        return definitionIdForVersion(engine, key, EngineSeed.deployNewVersion(engine, key, bpmn));
    }

    /* ============================ downgrade 1 — the removed SCOPE ============================ */

    @ParameterizedTest(name = "{0}")
    @MethodSource("calibratedEngines")
    void singleTokenScopeCollapseIsAnExecutableWarning_migratesAndStillCompletes(String engineId) throws Exception {
        Probes p = legs.get(engineId);
        String instanceId = startOn(p, p.scopeFrom());
        assertThat(activeExecutionActivityIds(p, instanceId)).containsExactlyInAnyOrder("scopeA", "stepA");

        JsonNode preview = previewOk(engineId, instanceId, p.scopeTo());

        // [M3]: the engine accepts this with an EMPTY mapping list on 6.8.0 and 7.1.0, so the BFF
        // must not refuse it. Re-blocking it would reinstate the false blocker #349 removed.
        assertThat(preview.path("executable").asBoolean(false)).isTrue();
        assertThat(preview.path("restBody").path("activityMappings")).isEmpty();

        JsonNode scope = activityEntry(preview, "scopeA");
        assertThat(scope.path("blocker").asBoolean(true)).isFalse();
        assertThat(scope.path("warning").asBoolean(false)).isTrue();
        assertThat(scope.path("status").asText()).isEqualTo("SCOPE_REMOVED");
        assertThat(findingCodes(scope)).containsExactly("ACTIVE_SCOPE_REMOVED");
        assertThat(scope.path("findings").get(0).path("severity").asText()).isEqualTo("WARNING");
        // §14.11: the corrected copy states the MEASURED behavior — one survivor, not "tokens".
        assertThat(scope.path("findings").get(0).path("detail").asText())
                .contains("exactly ONE token surviving a scope collapse")
                .contains("1 live token(s) are inside this scope");

        JsonNode leaf = activityEntry(preview, "stepA");
        assertThat(leaf.path("blocker").asBoolean(true)).isFalse();
        assertThat(findingCodes(leaf)).containsExactly("ACTIVE_IN_REMOVED_SCOPE");

        // …and it genuinely migrates through the full tier-3 rails.
        ResponseEntity<String> response =
                execute(engineId, instanceId, boundBody(preview, p.scopeTo(), "removed the scope in a bad deploy"));
        assertThat(response.getStatusCode())
                .as("execute body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);

        assertThat(currentDefinitionId(p, instanceId)).isEqualTo(p.scopeTo());
        // The scope execution is gone; the single token re-homed to the process root ([M3]).
        assertThat(activeExecutionActivityIds(p, instanceId)).containsExactly("stepA");

        // The one that matters for "do not over-block": the migrated instance is still WORKABLE.
        completeFirstTask(p, instanceId);
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(historicEndTime(p, instanceId)).isNotNull());
    }

    /* ====================== §14.11 — the LOSSY collapse is REFUSED ====================== */

    @ParameterizedTest(name = "{0}")
    @MethodSource("calibratedEngines")
    void twoConcurrentTokensInADissolvingScopeAreRefusedBeforeAnyEngineContact(String engineId) throws Exception {
        Probes p = legs.get(engineId);
        String instanceId = startOn(p, p.lossyFrom());
        assertThat(activeExecutionActivityIds(p, instanceId)).containsExactlyInAnyOrder("scopeP", "stepP1", "stepP2");

        JsonNode preview = previewOk(engineId, instanceId, p.lossyTo());

        assertThat(preview.path("executable").asBoolean(true)).isFalse();
        JsonNode scope = activityEntry(preview, "scopeP");
        assertThat(scope.path("status").asText()).isEqualTo("SCOPE_REMOVED");
        assertThat(scope.path("blocker").asBoolean(false)).isTrue();
        assertThat(findingCodes(scope)).containsExactly("SCOPE_COLLAPSE_TOKEN_LOSS");
        assertThat(scope.path("findings").get(0).path("severity").asText()).isEqualTo("BLOCKER_ADVICE");
        assertThat(scope.path("findings").get(0).path("detail").asText())
                .contains("2 live tokens are inside it")
                .contains("the engine will keep 1")
                .contains("Supplying a target mapping does not help");
        // Both leaves auto-map by id — that is exactly why the loss was invisible.
        assertThat(activityEntry(preview, "stepP1").path("blocker").asBoolean(true))
                .isFalse();
        assertThat(activityEntry(preview, "stepP2").path("blocker").asBoolean(true))
                .isFalse();

        ResponseEntity<String> response =
                execute(engineId, instanceId, boundBody(preview, p.lossyTo(), "try the lossy scope collapse"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(response.getBody()).path("code").asText()).isEqualTo("scope-collapse-token-loss");

        // Refused BEFORE engine contact: the instance is untouched AND no audit row exists (the
        // guard fires ahead of audit.beginPending, which is what "nothing was sent" means here).
        assertThat(currentDefinitionId(p, instanceId)).isEqualTo(p.lossyFrom());
        assertThat(activeExecutionActivityIds(p, instanceId)).containsExactlyInAnyOrder("scopeP", "stepP1", "stepP2");
        assertThat(auditRows(engineId, instanceId)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("calibratedEngines")
    void aScopeRENAMEWithThreeConcurrentTokensIsTheSameRefusal(String engineId) throws Exception {
        Probes p = legs.get(engineId);
        String instanceId = startOn(p, p.renameFrom());
        assertThat(activeExecutionActivityIds(p, instanceId))
                .containsExactlyInAnyOrder("scopeR", "stepR1", "stepR2", "stepR3");

        JsonNode preview = previewOk(engineId, instanceId, p.renameTo());

        assertThat(preview.path("executable").asBoolean(true)).isFalse();
        JsonNode scope = activityEntry(preview, "scopeR");
        assertThat(findingCodes(scope)).containsExactly("SCOPE_COLLAPSE_TOKEN_LOSS");
        assertThat(scope.path("findings").get(0).path("detail").asText()).contains("3 live tokens are inside it");
        assertThat(preview.path("summary").asText()).contains("silently destroy live work");

        ResponseEntity<String> response =
                execute(engineId, instanceId, boundBody(preview, p.renameTo(), "try the lossy scope rename"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(response.getBody()).path("code").asText()).isEqualTo("scope-collapse-token-loss");
        assertThat(currentDefinitionId(p, instanceId)).isEqualTo(p.renameFrom());
    }

    /**
     * The calibration this blocker rests on, kept LIVE against whichever engines the harness runs
     * (the [M9] precedent). Fires the migrate ENGINE-DIRECT (out of band, bypassing the BFF — never
     * a table write) on a throwaway instance and records exactly what the engine does with two
     * concurrent tokens in a dissolving scope. Measured 2026-08-04 on 6.8.0 AND 7.1.0: HTTP
     * <b>200</b>, {@code [scopeP, stepP1, stepP2] → [stepP2]} — one token gone, no error surfaced.
     *
     * <p>Only the load-bearing invariant is asserted: at least one token was DESTROYED on a success
     * response, i.e. re-lock decision 10's atomic-rejection backstop is structurally absent for
     * this shape. Should a future engine start preserving both (or start rejecting), this goes red
     * and the severity must be re-argued from fresh evidence — which is the point.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("calibratedEngines")
    void engineDirectProbeProvesTheCollapseDestroysTokensOnASuccessResponse(String engineId) {
        Probes p = legs.get(engineId);
        String probeInstance = startOn(p, p.lossyFrom());
        List<String> before = activeExecutionActivityIds(p, probeInstance);

        int status;
        String body;
        try {
            ResponseEntity<String> direct = engineDirectMigrate(p, probeInstance, p.lossyTo());
            status = direct.getStatusCode().value();
            body = String.valueOf(direct.getBody());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            status = e.getStatusCode().value();
            body = e.getResponseBodyAsString();
        }
        List<String> after = activeExecutionActivityIds(p, probeInstance);
        String observation = "engine-direct migrate of a 2-token scope collapse -> HTTP " + status + ", activity ids "
                + before + " -> " + after + ", body " + body;

        long survivors = after.stream().filter(id -> id.startsWith("stepP")).count();
        assertThat(survivors)
                .as(
                        "§14.11: the engine reports SUCCESS while destroying live tokens, so the estimate is the"
                                + " only place this can be caught. RECORDED: %s",
                        observation)
                .isLessThan(2);
        assertThat(status)
                .as("the collapse is not rejected — that is precisely the missing backstop. RECORDED: %s", observation)
                .isEqualTo(200);

        // Residue hygiene: this instance is now permanently parked (a join whose sibling is gone).
        EngineSeed.deleteInstanceQuietly(p.engine(), probeInstance);
    }

    /* =========================== downgrade 2 — the removed BOUNDARY =========================== */

    @ParameterizedTest(name = "{0}")
    @MethodSource("calibratedEngines")
    void removedBoundarySubscriptionIsNowAnExecutableWarning_andTheMigrationLandsThroughTheBff(String engineId)
            throws Exception {
        Probes p = legs.get(engineId);
        String instanceId = startOn(p, p.boundaryFrom());
        assertThat(activeExecutionActivityIds(p, instanceId)).containsExactlyInAnyOrder("stepC", "bndC");
        assertThat(timerJobDueDates(p, instanceId)).hasSize(1);

        JsonNode preview = previewOk(engineId, instanceId, p.boundaryRemoved());

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
                engineId, instanceId, boundBody(preview, p.boundaryRemoved(), "drop the obsolete deadline branch"));
        assertThat(response.getStatusCode())
                .as("execute body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);

        assertThat(currentDefinitionId(p, instanceId)).isEqualTo(p.boundaryRemoved());
        // [M4]: the subscription AND its timer job vanish with no error anywhere — exactly the
        // silent loss the WARNING exists to announce.
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(activeExecutionActivityIds(p, instanceId)).containsExactly("stepC");
                    assertThat(timerJobDueDates(p, instanceId)).isEmpty();
                });
    }

    /* ========================= the version-divergent clock INFO ([M5]) ========================= */

    @Test
    void unchangedBoundaryTimerStillRaisesTheClockResetInfo_dueDateRecordedNotAsserted() throws Exception {
        Probes p = legs.get("engine-a");
        String instanceId = startOn(p, p.boundaryFrom());
        List<String> before = timerJobDueDates(p, instanceId);
        assertThat(before).hasSize(1);

        JsonNode preview = previewOk("engine-a", instanceId, p.boundaryIdentical());

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

        ResponseEntity<String> response = execute(
                "engine-a", instanceId, boundBody(preview, p.boundaryIdentical(), "re-pin to the rebuilt version"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The timer is RE-SUBSCRIBED — that much is universal. Whether its due date moved is NOT:
        // 6.8.0 restarted the clock at migrate time, 7.1.0 preserved the original ([M5]). Asserting
        // either would enshrine one engine's behavior, so the observation is recorded in the
        // assertion description and only the version-neutral invariant is asserted.
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    List<String> after = timerJobDueDates(p, instanceId);
                    assertThat(after)
                            .as(
                                    "boundary timer due date before=%s after=%s (RECORDED, not asserted — [M5] is "
                                            + "version-divergent: reset on 6.8.0, preserved on 7.1.0)",
                                    before, after)
                            .hasSize(1);
                });
        assertThat(currentDefinitionId(p, instanceId)).isEqualTo(p.boundaryIdentical());
    }

    /* ================== the loud class: same id, different type ([M6]) ================== */

    @Test
    void sameIdTypeChangeWarnsLoudly_andTheNewBehaviorRunsDuringTheMigrateCall() throws Exception {
        Probes p = legs.get("engine-a");
        String instanceId = startOn(p, p.typeFrom());
        assertThat(activeExecutionActivityIds(p, instanceId)).containsExactly("stepT");

        JsonNode preview = previewOk("engine-a", instanceId, p.typeTo());
        assertThat(preview.path("executable").asBoolean(false)).isTrue();
        JsonNode step = activityEntry(preview, "stepT");
        assertThat(step.path("status").asText()).isEqualTo("TYPE_CHANGED");
        assertThat(step.path("warning").asBoolean(false)).isTrue();
        assertThat(findingCodes(step)).containsExactly("TYPE_CHANGED_SAME_ID");
        assertThat(step.path("findings").get(0).path("detail").asText())
                .contains("execute IMMEDIATELY as part of the migrate call");

        ResponseEntity<String> response =
                execute("engine-a", instanceId, boundBody(preview, p.typeTo(), "swap the manual step for automation"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // [M6]: the synchronous serviceTask ran AS PART OF the migrate call — the instance is
        // already finished by the time the BFF re-reads it.
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(historicEndTime(p, instanceId)).isNotNull();
                });
    }

    /* ===================== MI-root retention — the deliberate NON-downgrade ===================== */

    @Test
    void multiInstanceRootWithNoTargetKeepsTheBlockerAndExecuteRefuses422() throws Exception {
        Probes p = legs.get("engine-a");
        String instanceId = startOn(p, p.miFrom());
        assertThat(activeExecutionActivityIds(p, instanceId)).containsExactlyInAnyOrder("miScope", "stepM");

        JsonNode preview = previewOk("engine-a", instanceId, p.miTo());
        assertThat(preview.path("executable").asBoolean(true)).isFalse();
        JsonNode miScope = activityEntry(preview, "miScope");
        assertThat(miScope.path("status").asText()).isEqualTo("FLAGGED_UNMAPPED");
        assertThat(miScope.path("blocker").asBoolean(false)).isTrue();
        assertThat(findingCodes(miScope)).containsExactly("UNMAPPED_ACTIVE_ACTIVITY");
        assertThat(miScope.path("findings").get(0).path("severity").asText()).isEqualTo("BLOCKER_ADVICE");

        ResponseEntity<String> response =
                execute("engine-a", instanceId, boundBody(preview, p.miTo(), "attempt the MI scope removal"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(response.getBody()).path("code").asText()).isEqualTo("unmapped-activities");
        assertThat(currentDefinitionId(p, instanceId)).isEqualTo(p.miFrom()); // nothing happened
    }

    /**
     * §14.9's designated evidence-gatherer. The MI-root blocker is retained because NO multi-
     * instance case was calibrated when §14 was locked — not because the engine was proven to
     * reject it. This probe fires the migrate ENGINE-DIRECT (out of band, bypassing the BFF —
     * never a table write) on a throwaway instance and RECORDS what the engine actually does, so
     * a future {@code taxonomyVersion} bump can be argued from evidence instead of speculation.
     * It deliberately asserts no verdict about the engine: the only assertion is that the BFF's
     * retention is <b>deliberate</b>, i.e. unchanged by whatever the engine tolerates. Run on BOTH
     * calibrated majors — [M9] already found them DIVERGENT on the resulting state.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("calibratedEngines")
    void engineDirectProbeRecordsTheMultiInstanceOutcome_andTheBffRetentionIsDeliberate(String engineId)
            throws Exception {
        Probes p = legs.get(engineId);
        String probeInstance = startOn(p, p.miFrom());
        int status;
        String body;
        try {
            ResponseEntity<String> direct = engineDirectMigrate(p, probeInstance, p.miTo());
            status = direct.getStatusCode().value();
            body = String.valueOf(direct.getBody());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            status = e.getStatusCode().value();
            body = e.getResponseBodyAsString();
        }
        List<String> afterIds = activeExecutionActivityIds(p, probeInstance);
        String observation = "engine-direct migrate of an UNMAPPED multi-instance root on " + engineId + " -> HTTP "
                + status + ", activity ids now " + afterIds + ", body " + body;

        // A fresh instance of the same shape: the BFF still blocks, whatever the engine tolerates.
        String instanceId = startOn(p, p.miFrom());
        JsonNode preview = previewOk(engineId, instanceId, p.miTo());
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
     * and BOTH blocked kinds — the unsendable-document blocker and §14.11's token-loss blocker —
     * must hit BYTE-IDENTICAL rails: ADMIN floor, mandatory CAS binding, reason ≥10 chars, and the
     * prod typed-confirm. No finding — green, amber or red — shortcuts or adds a single guard, and
     * nothing moves in any refusal.
     */
    @Test
    void everyRailRefusesIdenticallyForGreen_warning_andBlockedEstimates() throws Exception {
        Probes p = legs.get("engine-a");
        record Estimate(String label, String fromDefinitionId, String toDefinitionId) {}
        List<Estimate> estimates = List.of(
                new Estimate("green (zero findings)", p.typeFrom(), p.typeSame()),
                new Estimate("warning-carrying", p.scopeFrom(), p.scopeTo()),
                new Estimate("blocked (nothing sendable)", p.miFrom(), p.miTo()),
                new Estimate("blocked (scope-collapse token loss)", p.lossyFrom(), p.lossyTo()));

        for (Estimate estimate : estimates) {
            String instanceId = startOn(p, estimate.fromDefinitionId());
            JsonNode preview = previewOk("engine-a", instanceId, estimate.toDefinitionId());
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
            assertThat(currentDefinitionId(p, instanceId))
                    .as("no rail refusal moved the instance — %s", label)
                    .isEqualTo(estimate.fromDefinitionId());
        }
    }

    /* ============================== the migrate-instance/v2 audit ============================== */

    @Test
    void executeAuditsMigrateInstanceV2WithTypedFindingsAndNoBffWarnings() throws Exception {
        Probes p = legs.get("engine-a");
        String instanceId = startOn(p, p.scopeFrom());
        JsonNode preview = previewOk("engine-a", instanceId, p.scopeTo());
        assertThat(execute("engine-a", instanceId, boundBody(preview, p.scopeTo(), "audit the typed findings payload"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    JsonNode rows = auditRows("engine-a", instanceId);
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

    private JsonNode previewOk(String engineId, String instanceId, String toDefinitionId) throws Exception {
        ResponseEntity<String> response = as("admin")
                .postForEntity(
                        "/api/instances/" + engineId + "/" + instanceId + "/migrate/preview",
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

    private JsonNode auditRows(String engineId, String instanceId) throws Exception {
        return mapper.readTree(as("admin")
                .getForEntity("/api/instances/" + engineId + "/" + instanceId + "/audit", String.class)
                .getBody());
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
    private String definitionIdForVersion(RestClient engine, String key, int version) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + key + "&version=" + version)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return String.valueOf(data.get(0).get("id"));
    }

    @SuppressWarnings("unchecked")
    private String startOn(Probes p, String definitionId) {
        Map<String, Object> started = p.engine()
                .post()
                .uri("/runtime/process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionId", definitionId))
                .retrieve()
                .body(Map.class);
        return String.valueOf(started.get("id"));
    }

    @SuppressWarnings("unchecked")
    private String currentDefinitionId(Probes p, String instanceId) {
        Map<String, Object> instance = p.engine()
                .get()
                .uri("/runtime/process-instances/" + instanceId)
                .retrieve()
                .body(Map.class);
        return String.valueOf(instance.get("processDefinitionId"));
    }

    /** Active executions as the pre-check sees them ([M2]) — the instance root is filtered out. */
    @SuppressWarnings("unchecked")
    private List<String> activeExecutionActivityIds(Probes p, String instanceId) {
        Map<String, Object> page = p.engine()
                .get()
                .uri("/runtime/executions?processInstanceId=" + instanceId + "&size=200")
                .retrieve()
                .body(Map.class);
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) page.get("data")) {
            Object activityId = row.get("activityId");
            if (activityId != null) {
                ids.add(String.valueOf(activityId));
            }
        }
        return ids.stream().distinct().sorted().toList();
    }

    /** Completes whichever user task the instance is parked on — the "still workable" proof. */
    @SuppressWarnings("unchecked")
    private void completeFirstTask(Probes p, String instanceId) {
        Map<String, Object> page = p.engine()
                .get()
                .uri("/runtime/tasks?processInstanceId=" + instanceId)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        assertThat(data).as("expected a live user task on %s", instanceId).isNotEmpty();
        p.engine()
                .post()
                .uri("/runtime/tasks/" + data.get(0).get("id"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", "complete"))
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private List<String> timerJobDueDates(Probes p, String instanceId) {
        Map<String, Object> page = p.engine()
                .get()
                .uri("/management/timer-jobs?processInstanceId=" + instanceId)
                .retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) page.get("data"))
                .stream().map(row -> String.valueOf(row.get("dueDate"))).toList();
    }

    @SuppressWarnings("unchecked")
    private String historicEndTime(Probes p, String instanceId) {
        Map<String, Object> instance = p.engine()
                .get()
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
    private ResponseEntity<String> engineDirectMigrate(Probes p, String instanceId, String toDefinitionId) {
        return p.engine()
                .post()
                .uri("/runtime/process-instances/" + instanceId + "/migrate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("toProcessDefinitionId", toDefinitionId, "activityMappings", List.of()))
                .retrieve()
                .toEntity(String.class);
    }
}
