package io.inspector.api;

import io.inspector.action.ActionResult;
import io.inspector.migration.MigrationPreview;
import io.inspector.migration.MigrationRequest;
import io.inspector.migration.MigrationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instance-migration routes (SPEC §5 tier-3) — explicit whitelisted paths, never a generic
 * proxy. The door check is the tier-3 ADMIN floor (unconditional every environment); the
 * service re-checks it plus the full guard ladder. {@code preview} is a BFF static auto-map
 * check (Flowable's REST API has no migration validator — P0 spike) and is read-only. Execute
 * lands in S2.
 */
@RestController
@RequestMapping("/api")
public class MigrationController {

    private final MigrationService migration;

    public MigrationController(MigrationService migration) {
        this.migration = migration;
    }

    @PostMapping("/instances/{engineId}/{instanceId}/migrate/preview")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public MigrationPreview preview(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @RequestBody(required = false) MigrationRequest request,
            Authentication authentication) {
        return migration.preview(
                engineId,
                instanceId,
                request != null ? request : new MigrationRequest(null, null, null, null, null, null, null, null),
                authentication);
    }

    @PostMapping("/instances/{engineId}/{instanceId}/migrate/execute")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public ActionResult execute(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @RequestBody MigrationRequest request,
            Authentication authentication) {
        return migration.execute(engineId, instanceId, request, authentication);
    }
}
