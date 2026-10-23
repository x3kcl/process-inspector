package io.inspector.api;

import io.inspector.action.ActionResult;
import io.inspector.migration.CohortAnalysisService;
import io.inspector.migration.CohortAnalysisView;
import io.inspector.migration.CohortExecuteRequest;
import io.inspector.migration.CohortExecuteResponse;
import io.inspector.migration.CohortMigrationService;
import io.inspector.migration.CohortPreviewRequest;
import io.inspector.migration.CohortPreviewResponse;
import io.inspector.migration.MigrationPreview;
import io.inspector.migration.MigrationRequest;
import io.inspector.migration.MigrationService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final CohortMigrationService cohorts;
    private final CohortAnalysisService analyses;

    public MigrationController(
            MigrationService migration, CohortMigrationService cohorts, CohortAnalysisService analyses) {
        this.migration = migration;
        this.cohorts = cohorts;
        this.analyses = analyses;
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

    /** Filtered cohort count (#482 S2). Does not call by-definition migrate. */
    @PostMapping("/migration/cohorts/preview")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public CohortPreviewResponse previewCohort(
            @RequestParam String engineId, @RequestBody CohortPreviewRequest request) {
        return cohorts.preview(engineId, request);
    }

    /** Per-instance cohort execute (#482 S4), capped. Prod confirm token is the count. */
    @PostMapping("/migration/cohorts/execute")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public CohortExecuteResponse executeCohort(
            @RequestParam String engineId, @RequestBody CohortExecuteRequest request, Authentication authentication) {
        return cohorts.execute(engineId, request, authentication);
    }

    /** Start a §14 estimate over the S2 cohort filter (#495). Does not migrate. */
    @PostMapping("/migration/analyses")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public CohortAnalysisView startAnalysis(
            @RequestParam String engineId, @RequestBody CohortPreviewRequest request, Authentication authentication) {
        return analyses.start(engineId, request, authentication);
    }

    /** The stored analysis: status, activity × code counts, and one page of the per-instance drill. */
    @GetMapping("/migration/analyses/{id}")
    @PreAuthorize("isAuthenticated()")
    public CohortAnalysisView getAnalysis(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return analyses.get(id, page, size, authentication);
    }
}
