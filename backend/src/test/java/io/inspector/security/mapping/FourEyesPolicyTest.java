package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.security.Role;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * TS-ESCALATION-01 (R-SAFE-14, CI-gating — §14): the four-eyes trigger matrix. A widen that slips
 * through single-actor would be a quiet self-grant = Sev1, so every escalation branch is pinned:
 * ladder-narrow is single-actor; self-widen, any wildcard ≥OPERATOR grant, any fleet create, and
 * any fleet removal demand four-eyes; the check is on the resolved change, not text.
 */
class FourEyesPolicyTest {

    private static final Set<String> EDITOR_IN_ORDERS = Set.of("orders-l1", "orders-l2");
    private static final Set<String> EDITOR_ELSEWHERE = Set.of("payments-l1");

    private boolean fourEyes(GrantChange c, Set<String> editorGroups) {
        return FourEyesPolicy.requiresFourEyes(c, editorGroups);
    }

    @Test
    void narrowingALadderGrantIsSingleActor() {
        assertThat(fourEyes(
                        GrantChange.ladderRemove("orders-l1", Role.OPERATOR, "orders-prod", "t1"), EDITOR_ELSEWHERE))
                .isFalse();
    }

    @Test
    void addingAScopedNonWildcardGrantToAnotherGroupIsSingleActor() {
        // RESPONDER on a concrete engine/tenant, editor not in the group → no widen, no breadth.
        assertThat(fourEyes(
                        GrantChange.ladderAdd("payments-l1", Role.RESPONDER, "orders-prod", "t1"), EDITOR_IN_ORDERS))
                .isFalse();
    }

    @Test
    void selfWidenDemandsFourEyes() {
        // Editor is in orders-l1; granting anything to orders-l1 is a self-widen.
        assertThat(fourEyes(GrantChange.ladderAdd("orders-l1", Role.RESPONDER, "orders-prod", "t1"), EDITOR_IN_ORDERS))
                .isTrue();
    }

    @Test
    void wildcardEngineOperatorGrantDemandsFourEyesEvenForAnotherGroup() {
        assertThat(fourEyes(GrantChange.ladderAdd("payments-l1", Role.OPERATOR, "*", "t1"), EDITOR_IN_ORDERS))
                .isTrue();
    }

    @Test
    void wildcardTenantAdminGrantDemandsFourEyes() {
        assertThat(fourEyes(GrantChange.ladderAdd("payments-l1", Role.ADMIN, "orders-prod", "*"), EDITOR_IN_ORDERS))
                .isTrue();
    }

    @Test
    void wildcardResponderGrantIsSingleActor() {
        // RESPONDER is below OPERATOR, so breadth does not trigger (editor not in the group).
        assertThat(fourEyes(GrantChange.ladderAdd("payments-l1", Role.RESPONDER, "*", "*"), EDITOR_IN_ORDERS))
                .isFalse();
    }

    @Test
    void everyFleetCreateDemandsFourEyes() {
        assertThat(fourEyes(GrantChange.fleetAdd("payments-l1", FleetGrant.REGISTRY_ADMIN), EDITOR_ELSEWHERE))
                .isTrue();
        assertThat(fourEyes(GrantChange.fleetAdd("payments-l1", FleetGrant.ACCESS_ADMIN), EDITOR_ELSEWHERE))
                .isTrue();
    }

    @Test
    void everyFleetRemovalDemandsFourEyes() {
        // Apex removal is a takeover, not a fail-safe narrowing (§3.4).
        assertThat(fourEyes(GrantChange.fleetRemove("payments-l1", FleetGrant.ACCESS_ADMIN), EDITOR_ELSEWHERE))
                .isTrue();
    }

    @Test
    void onlyAccessAdminChangesFireTheSecurityAlert() {
        assertThat(FourEyesPolicy.firesSecurityAlert(GrantChange.fleetAdd("g", FleetGrant.ACCESS_ADMIN)))
                .isTrue();
        assertThat(FourEyesPolicy.firesSecurityAlert(GrantChange.fleetAdd("g", FleetGrant.REGISTRY_ADMIN)))
                .isFalse();
        assertThat(FourEyesPolicy.firesSecurityAlert(GrantChange.ladderAdd("g", Role.ADMIN, "*", "*")))
                .isFalse();
    }
}
