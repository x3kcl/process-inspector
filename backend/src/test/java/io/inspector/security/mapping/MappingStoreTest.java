package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.security.Role;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Rung 1 (#464): {@link MappingStore}'s add/remove semantics per {@link GrantChange.Kind} — a
 * duplicate add and a missing remove are refused, never silently no-op'd — over mocked Spring Data
 * repositories. Transaction boundaries are proven by {@code MappingStoreDbIT}.
 */
class MappingStoreTest {

    private final GroupScopeGrantRepository ladder = mock(GroupScopeGrantRepository.class);
    private final GroupFleetGrantRepository fleet = mock(GroupFleetGrantRepository.class);
    private final MappingStore store = new MappingStore(ladder, fleet);

    private final GroupScopeGrantEntity ladderRow =
            new GroupScopeGrantEntity("ops", "OPERATOR", "e1", "t1", "ui", Instant.EPOCH);
    private final GroupFleetGrantEntity fleetRow =
            new GroupFleetGrantEntity("ops", FleetGrant.REGISTRY_ADMIN, "ui", Instant.EPOCH);

    @Test
    void emptyOnlyWhenBothTablesAreEmpty() {
        when(ladder.count()).thenReturn(0L);
        when(fleet.count()).thenReturn(0L);
        assertThat(store.isEmpty()).isTrue();

        when(fleet.count()).thenReturn(1L);
        assertThat(store.isEmpty()).isFalse();

        when(ladder.count()).thenReturn(1L);
        assertThat(store.isEmpty()).isFalse();
    }

    @Test
    void seedWritesBothTablesAndLookupsDelegate() {
        store.seed(List.of(ladderRow), List.of(fleetRow));
        verify(ladder).saveAll(List.of(ladderRow));
        verify(fleet).saveAll(List.of(fleetRow));

        when(ladder.findById(3L)).thenReturn(Optional.of(ladderRow));
        when(fleet.findById(4L)).thenReturn(Optional.of(fleetRow));
        assertThat(store.ladderById(3L)).containsSame(ladderRow);
        assertThat(store.fleetById(4L)).containsSame(fleetRow);
    }

    @Test
    void ladderAddSavesAUiRowAndRefusesADuplicate() {
        GrantChange add = GrantChange.ladderAdd("ops", Role.OPERATOR, "e1", "t1");
        when(ladder.findByGroupNameAndRoleAndEngineIdAndTenantId("ops", "OPERATOR", "e1", "t1"))
                .thenReturn(Optional.empty());

        store.apply(add);

        ArgumentCaptor<GroupScopeGrantEntity> saved = ArgumentCaptor.forClass(GroupScopeGrantEntity.class);
        verify(ladder).save(saved.capture());
        assertThat(saved.getValue())
                .extracting(
                        GroupScopeGrantEntity::getGroupName,
                        GroupScopeGrantEntity::getRole,
                        GroupScopeGrantEntity::getEngineId,
                        GroupScopeGrantEntity::getTenantId,
                        GroupScopeGrantEntity::getSource)
                .containsExactly("ops", "OPERATOR", "e1", "t1", "ui");
        assertThat(saved.getValue().getId()).isNull();

        when(ladder.findByGroupNameAndRoleAndEngineIdAndTenantId("ops", "OPERATOR", "e1", "t1"))
                .thenReturn(Optional.of(ladderRow));
        assertThatThrownBy(() -> store.apply(add)).hasMessageContaining("grant already exists");
    }

    @Test
    void fleetAddSavesAUiRowAndRefusesADuplicate() {
        GrantChange add = GrantChange.fleetAdd("ops", FleetGrant.REGISTRY_ADMIN);
        when(fleet.findByGroupNameAndGrantKind("ops", "REGISTRY_ADMIN")).thenReturn(Optional.empty());

        store.apply(add);

        ArgumentCaptor<GroupFleetGrantEntity> saved = ArgumentCaptor.forClass(GroupFleetGrantEntity.class);
        verify(fleet).save(saved.capture());
        assertThat(saved.getValue().getGroupName()).isEqualTo("ops");
        assertThat(saved.getValue().getGrantKind()).isEqualTo(FleetGrant.REGISTRY_ADMIN);
        assertThat(saved.getValue().getSource()).isEqualTo("ui");
        assertThat(saved.getValue().getId()).isNull();

        when(fleet.findByGroupNameAndGrantKind("ops", "REGISTRY_ADMIN")).thenReturn(Optional.of(fleetRow));
        assertThatThrownBy(() -> store.apply(add)).hasMessageContaining("fleet grant already exists");
    }

    @Test
    void removesDeleteTheMatchedRowAndRefuseAMissingOne() {
        when(ladder.findByGroupNameAndRoleAndEngineIdAndTenantId("ops", "OPERATOR", "e1", "t1"))
                .thenReturn(Optional.of(ladderRow));
        when(fleet.findByGroupNameAndGrantKind("ops", "REGISTRY_ADMIN")).thenReturn(Optional.of(fleetRow));

        store.apply(GrantChange.ladderRemove("ops", Role.OPERATOR, "e1", "t1"));
        store.apply(GrantChange.fleetRemove("ops", FleetGrant.REGISTRY_ADMIN));

        verify(ladder).delete(ladderRow);
        verify(fleet).delete(fleetRow);

        assertThatThrownBy(() -> store.apply(GrantChange.ladderRemove("ops", Role.VIEWER, "e1", "t1")))
                .hasMessageContaining("no such grant");
        assertThatThrownBy(() -> store.apply(GrantChange.fleetRemove("ops", FleetGrant.ACCESS_ADMIN)))
                .hasMessageContaining("no such fleet grant");
        verify(ladder, never()).save(any());
    }
}
