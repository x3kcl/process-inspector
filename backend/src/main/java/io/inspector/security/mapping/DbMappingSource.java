package io.inspector.security.mapping;

import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The DB-authoritative mapping source ({@code @Profile("db")}, IDP-SECURITY.md §6): reads the
 * {@code group_scope_grant} / {@code group_fleet_grant} store behind a short in-memory cache
 * (≤60s freshness, same TTL as the file source) so a committed grant applies to live sessions
 * within the window and check-time resolution never blocks on the DB. The env-bootstrap apex
 * ({@code inspector.security.mapping.access-admin-group}) is overlaid on every read as an
 * {@code ACCESS_ADMIN} fleet grant — the always-available lock-out floor, independent of store state.
 */
@Component
@Profile("db")
public class DbMappingSource implements MappingSource {

    private static final long TTL_SECONDS = 60;

    private final GroupScopeGrantRepository ladderRepo;
    private final GroupFleetGrantRepository fleetRepo;
    private final MappingProperties mapping;
    private final Clock clock;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(List.of(), List.of(), null));

    private record Snapshot(List<LadderGrantRow> ladder, List<FleetGrantRow> fleet, Instant loadedAt) {}

    public DbMappingSource(
            GroupScopeGrantRepository ladderRepo,
            GroupFleetGrantRepository fleetRepo,
            MappingProperties mapping,
            Clock clock) {
        this.ladderRepo = ladderRepo;
        this.fleetRepo = fleetRepo;
        this.mapping = mapping;
        this.clock = clock;
    }

    /** Force a cache reload (S4 calls this afterCommit so a committed grant is picked up at once). */
    public void refresh() {
        snapshot.set(load());
    }

    private Snapshot current() {
        Snapshot snap = snapshot.get();
        if (snap.loadedAt() != null
                && Duration.between(snap.loadedAt(), clock.instant()).getSeconds() < TTL_SECONDS) {
            return snap;
        }
        Snapshot reloaded = load();
        snapshot.set(reloaded);
        return reloaded;
    }

    private Snapshot load() {
        List<LadderGrantRow> ladder = ladderRepo.findAll().stream()
                .map(e -> new LadderGrantRow(
                        e.getGroupName(), Role.valueOf(e.getRole()), e.getEngineId(), e.getTenantId(), e.getSource()))
                .toList();
        List<FleetGrantRow> fleet = new java.util.ArrayList<>(fleetRepo.findAll().stream()
                .map(e -> new FleetGrantRow(e.getGroupName(), e.getGrantKind(), e.getSource()))
                .toList());
        // Env-bootstrap apex overlay (dedup if the store already grants it to the same group).
        String apex = mapping.accessAdminGroupOrNull();
        if (apex != null
                && fleet.stream()
                        .noneMatch(r -> r.grant() == FleetGrant.ACCESS_ADMIN
                                && r.group().equals(apex))) {
            fleet.add(new FleetGrantRow(apex, FleetGrant.ACCESS_ADMIN, "env-bootstrap"));
        }
        return new Snapshot(ladder, List.copyOf(fleet), clock.instant());
    }

    @Override
    public Set<ScopeGrant> grantsForGroups(Collection<String> groups) {
        Set<ScopeGrant> grants = new LinkedHashSet<>();
        for (LadderGrantRow row : current().ladder()) {
            if (groups.contains(row.group())) {
                grants.add(new ScopeGrant(row.role(), row.engineId(), row.tenantId()));
            }
        }
        return grants;
    }

    @Override
    public Set<Role> rolesForGroups(Collection<String> groups) {
        Set<Role> roles = new LinkedHashSet<>();
        for (ScopeGrant grant : grantsForGroups(groups)) {
            roles.add(grant.role());
        }
        return roles;
    }

    @Override
    public Set<FleetGrant> fleetGrantsForGroups(Collection<String> groups) {
        Set<FleetGrant> grants = new LinkedHashSet<>();
        for (FleetGrantRow row : current().fleet()) {
            if (groups.contains(row.group())) {
                grants.add(row.grant());
            }
        }
        return grants;
    }

    @Override
    public List<LadderGrantRow> allLadderGrants() {
        return current().ladder();
    }

    @Override
    public List<FleetGrantRow> allFleetGrants() {
        return current().fleet();
    }
}
