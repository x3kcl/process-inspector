package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.security.mapping.MappingSource.FleetGrantRow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (#464): the DB-authoritative mapping source — group resolution, the env-bootstrap apex
 * overlay (always present, never duplicated) and the ≤60s cache with explicit refresh — driven by a
 * mutable clock instead of sleeps.
 */
class DbMappingSourceTest {

    private static final Instant T0 = Instant.parse("2026-11-12T08:00:00Z");

    private final GroupScopeGrantRepository ladder = mock(GroupScopeGrantRepository.class);
    private final GroupFleetGrantRepository fleet = mock(GroupFleetGrantRepository.class);
    private final MutableClock clock = new MutableClock(T0);

    private DbMappingSource source(String envApex) {
        return new DbMappingSource(ladder, fleet, new MappingProperties("db", envApex), clock);
    }

    private void store(List<GroupScopeGrantEntity> ladderRows, List<GroupFleetGrantEntity> fleetRows) {
        when(ladder.findAll()).thenReturn(ladderRows);
        when(fleet.findAll()).thenReturn(fleetRows);
    }

    @Test
    void groupsResolveToTheirLadderGrantsRolesAndFleetGrants() {
        store(
                List.of(
                        new GroupScopeGrantEntity("ops", "OPERATOR", "e1", "t1", "ui", T0),
                        new GroupScopeGrantEntity("ops", "VIEWER", "*", "*", "file-seed", T0),
                        new GroupScopeGrantEntity("other", "ADMIN", "*", "*", "ui", T0)),
                List.of(new GroupFleetGrantEntity("ops", FleetGrant.REGISTRY_ADMIN, "ui", T0)));
        DbMappingSource src = source(null);

        assertThat(src.grantsForGroups(List.of("ops")))
                .containsExactly(new ScopeGrant(Role.OPERATOR, "e1", "t1"), ScopeGrant.global(Role.VIEWER));
        assertThat(src.rolesForGroups(List.of("ops"))).containsExactly(Role.OPERATOR, Role.VIEWER);
        assertThat(src.fleetGrantsForGroups(List.of("ops"))).containsExactly(FleetGrant.REGISTRY_ADMIN);
        assertThat(src.grantsForGroups(List.of("nobody"))).isEmpty();
        assertThat(src.allLadderGrants()).hasSize(3);
        assertThat(src.allLadderGrants().get(1).source()).isEqualTo("file-seed");
        assertThat(src.allFleetGrants()).containsExactly(new FleetGrantRow("ops", FleetGrant.REGISTRY_ADMIN, "ui"));
    }

    @Test
    void theEnvApexIsOverlaidOnceAndNeverDuplicated() {
        store(List.of(), List.of(new GroupFleetGrantEntity("admins-b", FleetGrant.ACCESS_ADMIN, "ui", T0)));
        assertThat(source("admins-a").allFleetGrants())
                .containsExactly(
                        new FleetGrantRow("admins-b", FleetGrant.ACCESS_ADMIN, "ui"),
                        new FleetGrantRow("admins-a", FleetGrant.ACCESS_ADMIN, "env-bootstrap"));
        assertThat(source("admins-a").fleetGrantsForGroups(Set.of("admins-a")))
                .containsExactly(FleetGrant.ACCESS_ADMIN);

        // The store already grants the env apex group → no second row.
        store(List.of(), List.of(new GroupFleetGrantEntity("admins-a", FleetGrant.ACCESS_ADMIN, "ui", T0)));
        assertThat(source("admins-a").allFleetGrants()).hasSize(1);

        // A blank env apex is no floor at all.
        assertThat(source(" ").allFleetGrants()).hasSize(1);
    }

    @Test
    void readsAreCachedForSixtySecondsAndRefreshReloadsAtOnce() {
        store(List.of(), List.of());
        DbMappingSource src = source(null);

        src.allLadderGrants();
        clock.advanceSeconds(59);
        src.allLadderGrants();
        verify(ladder, times(1)).findAll();

        clock.advanceSeconds(1);
        src.allLadderGrants();
        verify(ladder, times(2)).findAll();

        src.refresh();
        verify(ladder, times(3)).findAll();
        src.allFleetGrants(); // the refreshed snapshot is fresh — no extra load
        verify(fleet, times(3)).findAll();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
