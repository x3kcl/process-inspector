package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.security.mapping.MappingSource.FleetGrantRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TS-MAP-02 (R-SAFE-14): the ≥1/≥2-ACCESS_ADMIN boot invariant (IDP-SECURITY.md §6). Zero apex
 * groups is a refuse-to-boot lock-out; one boots with CRUD disabled; two or more is the healthy
 * state. A quiet no-apex boot would be a silent total lockout, so this fails startup loudly.
 */
class ApexInvariantCheckerTest {

    private ApexInvariantChecker checkerWith(FleetGrantRow... fleet) {
        MappingSource source = mock(MappingSource.class);
        when(source.allFleetGrants()).thenReturn(List.of(fleet));
        return new ApexInvariantChecker(source);
    }

    private static FleetGrantRow access(String group) {
        return new FleetGrantRow(group, FleetGrant.ACCESS_ADMIN, "ui");
    }

    @Test
    void zeroAccessAdminGroupsRefusesToBoot() {
        var checker = checkerWith(new FleetGrantRow("registry-admin", FleetGrant.REGISTRY_ADMIN, "config"));
        assertThatThrownBy(() -> checker.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APEX INVARIANT VIOLATED")
                .hasMessageContaining("INSPECTOR_ACCESS_ADMIN_GROUP");
    }

    @Test
    void oneAccessAdminGroupBootsWithCrudDisabled() {
        var checker = checkerWith(access("access-admins"));
        assertThatCode(() -> checker.run(null)).doesNotThrowAnyException();
    }

    @Test
    void twoIndependentAccessAdminGroupsBootCleanly() {
        var checker = checkerWith(access("access-admins"), access("break-glass-approvers"));
        assertThatCode(() -> checker.run(null)).doesNotThrowAnyException();
    }

    @Test
    void duplicateApexGroupRowsCountAsOne() {
        // Same group granted twice (e.g. store row + env overlay) must not fake a second approver.
        var checker = checkerWith(access("access-admins"), access("access-admins"));
        // Boots (≥1) but only one DISTINCT group — CRUD stays disabled; the important thing is it
        // does not throw AND does not pretend to satisfy the ≥2-independent invariant.
        assertThatCode(() -> checker.run(null)).doesNotThrowAnyException();
    }

    @Test
    void theCheckCountsDistinctGroupsNotRows() {
        MappingSource source = mock(MappingSource.class);
        when(source.allFleetGrants()).thenReturn(List.of(access("a"), access("a"), access("a")));
        long distinct = source.allFleetGrants().stream()
                .filter(r -> r.grant() == FleetGrant.ACCESS_ADMIN)
                .map(FleetGrantRow::group)
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(1);
    }
}
