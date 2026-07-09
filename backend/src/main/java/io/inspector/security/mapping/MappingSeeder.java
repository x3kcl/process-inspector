package io.inspector.security.mapping;

import io.inspector.audit.AuditService;
import io.inspector.security.ScopeGrant;
import io.inspector.security.ScopeMappingService;
import io.inspector.security.SecurityProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * File→DB seed import (IDP-SECURITY.md §6): on boot under {@code mapping-source: db}, an EMPTY
 * store imports the mounted YAML's ladder grants + the config {@code REGISTRY_ADMIN} group as
 * one-time {@code file-seed} rows (audited {@code mapping-seed}); a NON-empty store is left alone
 * (DB wins, file drift is reported, never silently re-imported). The env-bootstrap {@code ACCESS_ADMIN}
 * apex is deliberately NOT seeded — it stays an always-available overlay (the lock-out floor), so it
 * can't be removed via CRUD. Runs before the apex invariant check (@Order).
 */
@Component
@Profile("db")
@Order(1)
public class MappingSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MappingSeeder.class);

    private final MappingStore store;
    private final ScopeMappingService file;
    private final SecurityProperties security;
    private final DbMappingSource dbSource;
    private final AuditService audit;
    private final Clock clock;

    public MappingSeeder(
            MappingStore store,
            ScopeMappingService file,
            SecurityProperties security,
            DbMappingSource dbSource,
            AuditService audit,
            Clock clock) {
        this.store = store;
        this.file = file;
        this.security = security;
        this.dbSource = dbSource;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!store.isEmpty()) {
            return; // DB-authoritative once seeded; drift (if any) is reported, never re-imported.
        }
        Instant now = clock.instant();
        List<GroupScopeGrantEntity> ladder = new ArrayList<>();
        for (Map.Entry<String, List<ScopeGrant>> entry : file.allGrantsByGroup().entrySet()) {
            for (ScopeGrant g : entry.getValue()) {
                ladder.add(new GroupScopeGrantEntity(
                        entry.getKey(), g.role().name(), g.engineId(), g.tenantId(), "file-seed", now));
            }
        }
        // The config REGISTRY_ADMIN group migrates into the store so the fleet grant survives the
        // switch to db mode (the ACCESS_ADMIN env apex stays an overlay, never a seed row).
        List<GroupFleetGrantEntity> fleet = new ArrayList<>();
        fleet.add(new GroupFleetGrantEntity(
                security.registryAdminGroupOrDefault(), FleetGrant.REGISTRY_ADMIN, "file-seed", now));

        store.seed(ladder, fleet);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ladderGrantsSeeded", ladder.size());
        payload.put("fleetGrantsSeeded", fleet.size());
        audit.recordConfigEvent("mapping-seed", "system", true, payload);
        dbSource.refresh();
        log.info(
                "group→scope mapping DB store was empty — seeded {} ladder + {} fleet grant(s) from file/config",
                ladder.size(),
                fleet.size());
    }
}
