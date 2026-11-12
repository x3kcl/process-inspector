package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditService;
import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.security.ScopeMappingService;
import io.inspector.security.SecurityProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Rung 1 (#464): the file→DB seed import runs once on an EMPTY store only, imports every file ladder
 * grant plus the config REGISTRY_ADMIN group as {@code file-seed} rows, audits it and refreshes the
 * cache — and never seeds the env ACCESS_ADMIN apex (it stays an overlay).
 */
class MappingSeederTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    private final MappingStore store = mock(MappingStore.class);
    private final ScopeMappingService file = mock(ScopeMappingService.class);
    private final SecurityProperties security = mock(SecurityProperties.class);
    private final DbMappingSource dbSource = mock(DbMappingSource.class);
    private final AuditService audit = mock(AuditService.class);
    private final MappingSeeder seeder =
            new MappingSeeder(store, file, security, dbSource, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aNonEmptyStoreIsLeftAlone() {
        when(store.isEmpty()).thenReturn(false);

        seeder.run(null);

        verify(store).isEmpty();
        verifyNoInteractions(file, audit, dbSource);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyStoreImportsTheFileLadderAndTheRegistryAdminGroupOnce() {
        when(store.isEmpty()).thenReturn(true);
        Map<String, List<ScopeGrant>> fileGrants = new LinkedHashMap<>();
        fileGrants.put("ops", List.of(new ScopeGrant(Role.OPERATOR, "e1", "t1"), ScopeGrant.global(Role.VIEWER)));
        fileGrants.put("auditors", List.of(ScopeGrant.global(Role.VIEWER)));
        when(file.allGrantsByGroup()).thenReturn(fileGrants);
        when(security.registryAdminGroupOrDefault()).thenReturn("registry-admins");

        seeder.run(null);

        ArgumentCaptor<List<GroupScopeGrantEntity>> ladder = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<GroupFleetGrantEntity>> fleet = ArgumentCaptor.forClass(List.class);
        verify(store).seed(ladder.capture(), fleet.capture());
        assertThat(ladder.getValue())
                .extracting(
                        GroupScopeGrantEntity::getGroupName,
                        GroupScopeGrantEntity::getRole,
                        GroupScopeGrantEntity::getSource)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ops", "OPERATOR", "file-seed"),
                        org.assertj.core.groups.Tuple.tuple("ops", "VIEWER", "file-seed"),
                        org.assertj.core.groups.Tuple.tuple("auditors", "VIEWER", "file-seed"));
        assertThat(fleet.getValue()).singleElement().satisfies(f -> {
            assertThat(f.getGroupName()).isEqualTo("registry-admins");
            assertThat(f.getGrantKind()).isEqualTo(FleetGrant.REGISTRY_ADMIN); // never ACCESS_ADMIN
        });
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordConfigEvent(eq("mapping-seed"), eq("system"), eq(true), payload.capture());
        assertThat(payload.getValue()).containsEntry("ladderGrantsSeeded", 3).containsEntry("fleetGrantsSeeded", 1);
        verify(dbSource).refresh();
    }

    @Test
    void anEmptyFileStillSeedsTheRegistryAdminGroup() {
        when(store.isEmpty()).thenReturn(true);
        when(file.allGrantsByGroup()).thenReturn(Map.of());
        when(security.registryAdminGroupOrDefault()).thenReturn("registry-admin");

        seeder.run(null);

        verify(audit).recordConfigEvent(eq("mapping-seed"), eq("system"), eq(true), anyMap());
        verify(store).seed(eq(List.of()), org.mockito.ArgumentMatchers.argThat(l -> l.size() == 1));
    }
}
