package io.inspector.client;

import static io.inspector.client.RemainingBodyWire.P;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the CMMN PLAN-ITEM verb request bodies (#720): the exact bytes the BFF sends
 * for a plan-item instance action (trigger / enable / disable) and for a case change-state
 * (activate / move to available / terminate ONE plan-item definition). Every body has exactly one
 * key — the two change-state lists that do not apply are absent, never {@code null} or
 * {@code []} — so the wire is compared as the exact string. The case-level verbs (terminate,
 * delete) are DELETEs without a body.
 */
class PlanItemVerbBodyWireShapeTest {

    private RemainingBodyWire wire;

    @BeforeEach
    void setUp() {
        wire = new RemainingBodyWire();
    }

    @AfterEach
    void tearDown() {
        wire.close();
    }

    @Test
    void triggeringAPlanItemSendsTheActionAndNothingElse() {
        wire.cmmn.planItemAction(wire.engine, P, "pii1", "trigger");

        wire.pins(
                "PUT",
                "/cmmn-api/cmmn-runtime/plan-item-instances/pii1",
                "{\"action\":\"trigger\"}",
                new PlanItemActionRequest("trigger"));
    }

    @Test
    void enablingAPlanItemSendsTheActionVerbatim() {
        wire.cmmn.planItemAction(wire.engine, P, "pii1", "enable");

        wire.pins(
                "PUT",
                "/cmmn-api/cmmn-runtime/plan-item-instances/pii1",
                "{\"action\":\"enable\"}",
                new PlanItemActionRequest("enable"));
    }

    @Test
    void aPlanItemActionWithoutAnActionIsRefusedBeforeAnyEngineByte() {
        // The map body threw on a null action; it never became {} or {"action":null}.
        assertThatThrownBy(() -> wire.cmmn.planItemAction(wire.engine, P, "pii1", null))
                .isInstanceOf(NullPointerException.class);
        wire.sentNothing();
        assertThatThrownBy(() -> new PlanItemActionRequest(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void activatingAPlanItemDefinitionSendsItsIdInTheActivateListOnly() {
        wire.cmmn.changePlanItemState(wire.engine, P, "case1", "def1", "activate");

        wire.pins(
                "POST",
                "/cmmn-api/cmmn-runtime/case-instances/case1/change-state",
                "{\"activatePlanItemDefinitionIds\":[\"def1\"]}",
                ChangePlanItemStateRequest.of("activate", "def1"));
    }

    @Test
    void movingAPlanItemDefinitionToAvailableSendsItsIdInTheMoveToAvailableListOnly() {
        wire.cmmn.changePlanItemState(wire.engine, P, "case1", "def1", "available");

        wire.pins(
                "POST",
                "/cmmn-api/cmmn-runtime/case-instances/case1/change-state",
                "{\"moveToAvailablePlanItemDefinitionIds\":[\"def1\"]}",
                ChangePlanItemStateRequest.of("available", "def1"));
    }

    @Test
    void terminatingAPlanItemDefinitionSendsItsIdInTheTerminateListOnly() {
        wire.cmmn.changePlanItemState(wire.engine, P, "case1", "def1", "terminate");

        wire.pins(
                "POST",
                "/cmmn-api/cmmn-runtime/case-instances/case1/change-state",
                "{\"terminatePlanItemDefinitionIds\":[\"def1\"]}",
                ChangePlanItemStateRequest.of("terminate", "def1"));
    }

    @Test
    void anUnknownChangeStateActionIsRefusedBeforeAnyEngineByte() {
        assertThatThrownBy(() -> wire.cmmn.changePlanItemState(wire.engine, P, "case1", "def1", "complete"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete");
        wire.sentNothing();
        assertThatThrownBy(() -> ChangePlanItemStateRequest.of("complete", "def1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete");
    }

    @Test
    void aChangeStateWithoutADefinitionIdIsRefusedBeforeAnyEngineByte() {
        // The map body threw on a null id; it never became {"activatePlanItemDefinitionIds":[null]}.
        assertThatThrownBy(() -> wire.cmmn.changePlanItemState(wire.engine, P, "case1", null, "activate"))
                .isInstanceOf(NullPointerException.class);
        wire.sentNothing();
        assertThatThrownBy(() -> ChangePlanItemStateRequest.of("activate", null))
                .isInstanceOf(NullPointerException.class);
    }
}
