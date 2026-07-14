package io.inspector.surgery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.ActionResult;
import io.inspector.action.GuardRefusedException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 for the flow-surgery rails: guard ordering (refusals before the audit gate,
 * audit INSERT before the ONE engine call), the MI/parallel/suspended guardrails against
 * the REAL parsed structure (fixture = live-probed engine artifacts, see
 * {@link BpmnStructureTest}), and the restart variable-portability filter. Live wire
 * semantics are rung 4 — FlowSurgeryIT.
 */
class FlowSurgeryServiceTest {

    private static final String DEV = "dev-engine";
    private static final String PROD = "prod-engine";
    private static final String OLD = "old-engine"; // healthy but pre-6.4: no change-state
    private static final String COLD = "cold-engine"; // never probed: capabilities null
    private static final String DEF_ID = "demoFlowSurgery:1:abc";
    private static final String REASON = "moving the token off the failed node per INC-42";

    private final ProcessApiClient client = mock(ProcessApiClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private final io.inspector.audit.ProtectedDefinitionRepository protectedDefinitions =
            mock(io.inspector.audit.ProtectedDefinitionRepository.class);
    private final Authentication operator = new TestingAuthenticationToken("op", "n/a", "ROLE_OPERATOR");

    private FlowSurgeryService service;
    private AuditEntry pendingEntry;

    @BeforeEach
    void setUp() throws IOException {
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(
                        TestEngines.engine(DEV, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE),
                        TestEngines.engine(PROD, "http://localhost:1", EngineEnvironment.PROD, EngineMode.READ_WRITE),
                        TestEngines.engine(OLD, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE),
                        TestEngines.engine(COLD, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));
        registry.updateHealth(DEV, probed("6.8.0"));
        registry.updateHealth(PROD, probed("6.8.0"));
        registry.updateHealth(OLD, probed("6.3.1"));
        // COLD keeps EngineHealth.unknown() — capabilities null, as before the first probe.

        service = new FlowSurgeryService(
                registry,
                client,
                new BpmnStructureService(client),
                audit,
                rbac,
                new io.inspector.audit.ProtectionGuard(protectedInstances, protectedDefinitions, rbac),
                new io.inspector.action.TicketPolicy(new io.inspector.config.AuditProperties(null, null, null)));

        when(rbac.hasRoleOn(any(), any(), anyString())).thenReturn(true);
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());
        when(protectedDefinitions.findById(any())).thenReturn(Optional.empty());

        pendingEntry = new AuditEntry(
                UUID.nameUUIDFromBytes("audit".getBytes(StandardCharsets.UTF_8)),
                "corr-1",
                "op",
                Instant.parse("2026-07-06T12:00:00Z"),
                DEV,
                null,
                "pi-1",
                FlowSurgeryService.CHANGE_STATE_ACTION,
                null,
                null,
                null,
                false);
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pendingEntry);

        Map<String, Object> running = new HashMap<>();
        running.put("id", "pi-1");
        running.put("processDefinitionId", DEF_ID);
        running.put("suspended", false);
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(running);
        when(client.getProcessDefinitionModel(any(), eq(CallPriority.INTERACTIVE), eq(DEF_ID)))
                .thenReturn(modelJson());
        when(client.processDefinitionResourceData(any(), eq(CallPriority.INTERACTIVE), eq(DEF_ID)))
                .thenReturn(xml());
        when(client.listExecutions(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(Map.of("id", "pi-1"), Map.of("id", "e-2", "activityId", "stepOne")), 2, 0, 100));
    }

    private static EngineHealth probed(String version) {
        return new EngineHealth(
                true, version, null, 0, EngineCapabilities.fromVersion(version, true), null, null, null);
    }

    private static ChangeStateRequest move(String reason, String source, String target) {
        return new ChangeStateRequest(reason, null, List.of(source), List.of(target));
    }

    private static GuardRefusedException refusal(Throwable t) {
        return (GuardRefusedException) t;
    }

    /* ---------------- preview: the BFF simulation ---------------- */

    @Test
    void previewReturnsTheExactRestBodyAndAuditsNothing() {
        ChangeStatePreview preview =
                service.previewChangeState(DEV, "pi-1", move(null, "stepOne", "stepTwo"), operator);

        assertThat(preview.method()).isEqualTo("POST");
        assertThat(preview.enginePath()).isEqualTo("/runtime/process-instances/pi-1/change-state");
        assertThat(preview.payload())
                .containsExactly(
                        Map.entry("cancelActivityIds", List.of("stepOne")),
                        Map.entry("startActivityIds", List.of("stepTwo")));
        assertThat(preview.summary())
                .contains("'Step one'")
                .contains("'Step two'")
                .contains("pi-1");
        assertThat(preview.simulationNote()).contains("no dry-run");
        assertThat(preview.warnings()).isEmpty();
        verifyNoInteractions(audit); // simulation only — nothing on the record, nothing mutated
        verify(client, never()).changeActivityState(any(), any(), any(), any());
    }

    @Test
    void previewWarnsWhenTheTargetSitsOnAParallelBranch() {
        ChangeStatePreview preview =
                service.previewChangeState(DEV, "pi-1", move(null, "stepOne", "branchA"), operator);

        assertThat(preview.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("parallel-branch-target");
            assertThat(w.message()).contains("sibling");
        });
    }

    /* ---------------- the guardrails ---------------- */

    @Test
    void jumpIntoAMultiInstanceBodyIs422() {
        assertThatThrownBy(() -> service.previewChangeState(DEV, "pi-1", move(null, "stepOne", "miTask"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> {
                    assertThat(refusal(e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(refusal(e).code()).isEqualTo("multi-instance-body");
                    assertThat(e.getMessage()).contains("miSub");
                });
        verifyNoInteractions(audit);
    }

    @Test
    void jumpOutOfAMultiInstanceBodyIs422Too() {
        // The MI check outranks the source-active check: refused for the RIGHT reason.
        assertThatThrownBy(() -> service.executeChangeState(DEV, "pi-1", move(REASON, "miTask", "stepTwo"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo("multi-instance-body"));
        verifyNoInteractions(audit);
        verify(client, never()).changeActivityState(any(), any(), any(), any());
    }

    @Test
    void suspendedInstanceIsBlockedWithActivateFirstGuidance() {
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(Map.of("id", "pi-1", "processDefinitionId", DEF_ID, "suspended", true));

        assertThatThrownBy(() -> service.previewChangeState(DEV, "pi-1", move(null, "stepOne", "stepTwo"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> {
                    assertThat(refusal(e).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal(e).code()).isEqualTo("instance-suspended");
                    assertThat(e.getMessage()).contains("activate");
                });
    }

    @Test
    void unknownActivityIs422() {
        assertThatThrownBy(() -> service.previewChangeState(DEV, "pi-1", move(null, "stepOne", "nope"), operator))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo("unknown-activity"));
    }

    @Test
    void staleSourceIsRefusedWithTheLiveActivitySet() {
        assertThatThrownBy(() -> service.previewChangeState(DEV, "pi-1", move(null, "stepTwo", "stepOne"), operator))
                .satisfies(e -> {
                    assertThat(refusal(e).code()).isEqualTo("source-not-active");
                    assertThat(e.getMessage()).contains("stepOne"); // the live truth, not the stale claim
                });
    }

    @Test
    void changeStateOnProdRequiresAdmin() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(PROD))).thenReturn(false);

        assertThatThrownBy(() -> service.previewChangeState(PROD, "pi-1", move(null, "stepOne", "stepTwo"), operator))
                .satisfies(e -> {
                    assertThat(refusal(e).code()).isEqualTo("rbac-denied");
                    assertThat(e.getMessage()).contains("ADMIN");
                });
    }

    @Test
    void changeStateIsRefusedWhenTheInstancesOwnDefinitionIsProtected() {
        // #184: planChangeState already resolves processDefinitionId (for the BPMN structure
        // lookup) BEFORE requireUnprotectedOrAdmin now runs — free dual-scope checking. DEV's
        // role floor for change-state is OPERATOR (only PROD is ADMIN-gated), so this is a
        // genuine blocked-vs-allowed distinction, unlike #172's suspend-definition case.
        when(protectedDefinitions.findById(new io.inspector.audit.ProtectedDefinition.Key(DEV, "demoFlowSurgery")))
                .thenReturn(Optional.of(new io.inspector.audit.ProtectedDefinition(
                        DEV, "demoFlowSurgery", "incident freeze", "admin", Instant.parse("2026-07-01T00:00:00Z"))));
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(false);

        assertThatThrownBy(() -> service.executeChangeState(DEV, "pi-1", move(null, "stepOne", "stepTwo"), operator))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo("definition-protected"));
        verifyNoInteractions(audit);
    }

    @Test
    void capabilityGateRefusesPre64EnginesAndUnprobedOnes() {
        assertThatThrownBy(() -> service.previewChangeState(OLD, "pi-1", move(null, "stepOne", "stepTwo"), operator))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo("capability-unavailable"));
        assertThatThrownBy(() -> service.previewChangeState(COLD, "pi-1", move(null, "stepOne", "stepTwo"), operator))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo("capability-unknown"));
        verify(client, never()).getRuntimeProcessInstance(any(), any(), any()); // refused before any engine read
    }

    /* ---------------- execute: rails + audit truth ---------------- */

    @Test
    void executeWithoutAReasonIsRefusedBeforeTheAuditGate() {
        assertThatThrownBy(() -> service.executeChangeState(DEV, "pi-1", move(null, "stepOne", "stepTwo"), operator))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo("reason-required"));
        verifyNoInteractions(audit);
        verify(client, never()).changeActivityState(any(), any(), any(), any());
    }

    @Test
    void executeAuditsTheExactPayloadBeforeTheOneEngineCall() {
        ActionResult result = service.executeChangeState(DEV, "pi-1", move(REASON, "stepOne", "stepTwo"), operator);

        InOrder order = inOrder(audit, client);
        ArgumentCaptor<Map<String, Object>> auditPayload = ArgumentCaptor.captor();
        order.verify(audit)
                .beginPending(
                        eq("op"),
                        eq(DEV),
                        any(),
                        eq("pi-1"),
                        eq("change-state"),
                        eq(REASON),
                        any(),
                        auditPayload.capture(),
                        any());
        ArgumentCaptor<Map<String, Object>> wireBody = ArgumentCaptor.captor();
        order.verify(client).changeActivityState(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), wireBody.capture());
        order.verify(audit).close(eq(pendingEntry), eq(AuditOutcome.ok), eq(200), any(), eq(true));

        // The audit records the source/target activities AND the exact generated REST body.
        assertThat(wireBody.getValue())
                .containsExactly(
                        Map.entry("cancelActivityIds", List.of("stepOne")),
                        Map.entry("startActivityIds", List.of("stepTwo")));
        assertThat(auditPayload.getValue()).containsEntry("restPayload", wireBody.getValue());
        assertThat(auditPayload.getValue().get("sourceActivities").toString()).contains("Step one");
        assertThat(auditPayload.getValue().get("targetActivities").toString()).contains("Step two");
        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(result.deltaStatement()).contains("Token moved").contains("'Step two'");
    }

    /* ---------------- restart-as-new ---------------- */

    @Test
    void restartIsRefusedWhenTheDeadInstancesOwnDefinitionIsProtected() {
        // #184: the historic-instance fetch restart already needs (to confirm the instance is
        // actually dead) was reordered ahead of requireUnprotectedOrAdmin — free dual-scope
        // checking, no extra engine round-trip. Restart's role floor is OPERATOR, so this is a
        // genuine blocked-vs-allowed distinction.
        stubDeadInstance("pi-dead", "demoOrder:3:v3");
        when(protectedDefinitions.findById(new io.inspector.audit.ProtectedDefinition.Key(DEV, "demoOrder")))
                .thenReturn(Optional.of(new io.inspector.audit.ProtectedDefinition(
                        DEV, "demoOrder", "post-incident hold", "admin", Instant.parse("2026-07-01T00:00:00Z"))));
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(false);

        assertThatThrownBy(() ->
                        service.restartAsNew(DEV, "pi-dead", new RestartInstanceRequest(REASON, null, false), operator))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo("definition-protected"));
        verifyNoInteractions(audit);
    }

    @Test
    void restartRefusesARunningInstance() {
        assertThatThrownBy(() ->
                        service.restartAsNew(DEV, "pi-1", new RestartInstanceRequest(REASON, null, false), operator))
                .satisfies(e -> {
                    assertThat(refusal(e).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal(e).code()).isEqualTo("instance-still-running");
                });
        verifyNoInteractions(audit);
    }

    @Test
    void restartRefusesAnUnknownInstance() {
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("ghost")))
                .thenReturn(null);
        when(client.getHistoricProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("ghost")))
                .thenReturn(null);

        assertThatThrownBy(() ->
                        service.restartAsNew(DEV, "ghost", new RestartInstanceRequest(REASON, null, false), operator))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo("unknown-instance"));
    }

    @Test
    void restartCarriesPortableGlobalsAndReportsEveryStrippedVariable() {
        stubDeadInstance("pi-dead", "demoOrder:3:v3");
        when(client.listProcessDefinitionsByKey(
                        any(), eq(CallPriority.INTERACTIVE), eq("demoOrder"), any(), anyInt(), eq(1)))
                .thenReturn(new FlowablePage(List.of(Map.of("id", "demoOrder:4:v4")), 1, 0, 1));
        when(client.listHistoricVariableInstances(any(), eq(CallPriority.INTERACTIVE), eq("pi-dead"), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(
                                variableRow("amount", "integer", 42, "global", null),
                                variableRow("initiator", "string", "kermit", "global", null),
                                variableRow("blob", "serializable", null, "global", null),
                                variableRow("formNote", "string", "task-local", "global", "task-9"),
                                variableRow("loopLocal", "integer", 1, "local", null)),
                        5,
                        0,
                        500));
        when(client.startProcessInstance(any(), any(), any()))
                .thenReturn(Map.of("id", "pi-new", "processDefinitionId", "demoOrder:4:v4"));

        RestartInstanceResult result =
                service.restartAsNew(DEV, "pi-dead", new RestartInstanceRequest(REASON, null, false), operator);

        ArgumentCaptor<Map<String, Object>> startBody = ArgumentCaptor.captor();
        verify(client).startProcessInstance(any(), any(), startBody.capture());
        assertThat(startBody.getValue())
                .containsEntry("processDefinitionKey", "demoOrder") // latest: by KEY, never a silent pin
                .containsEntry("businessKey", "bk-9")
                .containsEntry("variables", List.of(Map.of("name", "amount", "type", "integer", "value", 42)))
                .doesNotContainKey("processDefinitionId");
        assertThat(result.newProcessInstanceId()).isEqualTo("pi-new");
        assertThat(result.carriedVariables()).containsExactly("amount");
        assertThat(result.skippedVariables()).containsKeys("initiator", "blob"); // the honesty report
        assertThat(result.skippedVariables()).doesNotContainKeys("formNote", "loopLocal"); // never instance state
        assertThat(result.deltaStatement()).contains("pi-new").contains("LATEST");
    }

    @Test
    void restartWithPinnedVersionStartsOnTheOriginalDefinitionId() {
        stubDeadInstance("pi-dead", "demoOrder:3:v3");
        when(client.getProcessDefinition(any(), eq(CallPriority.INTERACTIVE), eq("demoOrder:3:v3")))
                .thenReturn(Map.of("id", "demoOrder:3:v3"));
        when(client.listHistoricVariableInstances(any(), eq(CallPriority.INTERACTIVE), eq("pi-dead"), anyInt()))
                .thenReturn(new FlowablePage(List.of(), 0, 0, 500));
        when(client.startProcessInstance(any(), any(), any()))
                .thenReturn(Map.of("id", "pi-new", "processDefinitionId", "demoOrder:3:v3"));

        RestartInstanceResult result =
                service.restartAsNew(DEV, "pi-dead", new RestartInstanceRequest(REASON, null, true), operator);

        ArgumentCaptor<Map<String, Object>> startBody = ArgumentCaptor.captor();
        verify(client).startProcessInstance(any(), any(), startBody.capture());
        assertThat(startBody.getValue())
                .containsEntry("processDefinitionId", "demoOrder:3:v3")
                .doesNotContainKey("processDefinitionKey");
        assertThat(result.deltaStatement()).contains("PINNED");
    }

    @Test
    void pinnedRestartRefusesWhenTheOriginalVersionIsGone() {
        stubDeadInstance("pi-dead", "demoOrder:3:v3");
        when(client.getProcessDefinition(any(), eq(CallPriority.INTERACTIVE), eq("demoOrder:3:v3")))
                .thenReturn(null);

        assertThatThrownBy(() ->
                        service.restartAsNew(DEV, "pi-dead", new RestartInstanceRequest(REASON, null, true), operator))
                .satisfies(e -> {
                    assertThat(refusal(e).code()).isEqualTo("definition-gone");
                    assertThat(e.getMessage()).contains("pinDefinitionVersion=false");
                });
        verifyNoInteractions(audit);
    }

    private void stubDeadInstance(String instanceId, String definitionId) {
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq(instanceId)))
                .thenReturn(null);
        Map<String, Object> historic = new HashMap<>();
        historic.put("id", instanceId);
        historic.put("processDefinitionId", definitionId);
        historic.put("businessKey", "bk-9");
        historic.put("endTime", "2026-07-06T10:00:00.000Z");
        when(client.getHistoricProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq(instanceId)))
                .thenReturn(historic);
    }

    private static Map<String, Object> variableRow(
            String name, String type, Object value, String scope, String taskId) {
        Map<String, Object> variable = new HashMap<>();
        variable.put("name", name);
        variable.put("type", type);
        variable.put("value", value);
        variable.put("scope", scope);
        Map<String, Object> row = new HashMap<>();
        row.put("variable", variable);
        row.put("taskId", taskId);
        return row;
    }

    /* ---------------- fixtures (live-probed artifacts, see BpmnStructureTest) ---------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelJson() throws IOException {
        try (InputStream in =
                FlowSurgeryServiceTest.class.getResourceAsStream("/surgery/demo-flow-surgery-model.json")) {
            return new ObjectMapper().readValue(in, Map.class);
        }
    }

    private static String xml() throws IOException {
        try (InputStream in =
                FlowSurgeryServiceTest.class.getResourceAsStream("/surgery/demo-flow-surgery.bpmn20.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
