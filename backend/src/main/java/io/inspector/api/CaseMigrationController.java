package io.inspector.api;

import io.inspector.action.ActionResult;
import io.inspector.migration.CaseCohortAnalysisService;
import io.inspector.migration.CaseCohortAnalysisView;
import io.inspector.migration.CaseCohortPreviewResponse;
import io.inspector.migration.CaseCohortRequest;
import io.inspector.migration.CaseCohortService;
import io.inspector.migration.CaseDefinitionMigrationRequest;
import io.inspector.migration.CaseDefinitionMigrationService;
import io.inspector.migration.CaseDefinitionVersionsResponse;
import io.inspector.migration.CaseMigrationBatchView;
import java.util.List;
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
 * Case migration at scale (#500) — explicit whitelisted paths, never a generic proxy. The cohort
 * routes mirror the process ones (count → analysis → {@code POST /api/bulk/migrate-cases}). The
 * three {@code migrate-*} doors reach engine routes that exist on Flowable 7.x only; on an older
 * engine the service refuses with ProblemDetail {@code version-gate} before any engine call.
 * Every mutating door is the tier-3 ADMIN floor; the service re-checks it plus the full ladder.
 */
@RestController
@RequestMapping("/api")
public class CaseMigrationController {

    private final CaseCohortService cohorts;
    private final CaseCohortAnalysisService analyses;
    private final CaseDefinitionMigrationService definitions;

    public CaseMigrationController(
            CaseCohortService cohorts, CaseCohortAnalysisService analyses, CaseDefinitionMigrationService definitions) {
        this.cohorts = cohorts;
        this.analyses = analyses;
        this.definitions = definitions;
    }

    /** Filtered case cohort count. Read-only. */
    @PostMapping("/case-migration/cohorts/preview")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public CaseCohortPreviewResponse previewCohort(
            @RequestParam String engineId, @RequestBody CaseCohortRequest request) {
        return cohorts.preview(engineId, request);
    }

    /** Start the per-case pre-check over the cohort filter. Does not migrate. */
    @PostMapping("/case-migration/analyses")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public CaseCohortAnalysisView startAnalysis(
            @RequestParam String engineId, @RequestBody CaseCohortRequest request, Authentication authentication) {
        return analyses.start(engineId, request, authentication);
    }

    @GetMapping("/case-migration/analyses/{id}")
    @PreAuthorize("isAuthenticated()")
    public CaseCohortAnalysisView getAnalysis(@PathVariable UUID id, Authentication authentication) {
        return analyses.get(id, authentication);
    }

    /** The on-ramp: per-version RUNNING and ended case counts of one case key. Count-only. */
    @GetMapping("/case-definitions/{engineId}/{key}/versions")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public CaseDefinitionVersionsResponse versions(@PathVariable String engineId, @PathVariable String key) {
        return definitions.versions(engineId, key);
    }

    /** The engine's case-migration batches from this definition, with per-part tallies. Read-only. */
    @GetMapping("/case-definitions/{engineId}/{caseDefinitionId}/migration-batches")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public List<CaseMigrationBatchView> batches(@PathVariable String engineId, @PathVariable String caseDefinitionId) {
        return definitions.batches(engineId, caseDefinitionId);
    }

    /** Flowable 7.x only: batch-migrate every running case of one version. */
    @PostMapping("/case-definitions/{engineId}/{caseDefinitionId}/migrate-batch")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public ActionResult migrateBatch(
            @PathVariable String engineId,
            @PathVariable String caseDefinitionId,
            @RequestBody CaseDefinitionMigrationRequest request,
            Authentication authentication) {
        return definitions.migrateBatch(engineId, caseDefinitionId, request, authentication);
    }

    /** Flowable 7.x only: re-point the history of every ended case of one version. */
    @PostMapping("/case-definitions/{engineId}/{caseDefinitionId}/migrate-historic")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public ActionResult migrateHistoric(
            @PathVariable String engineId,
            @PathVariable String caseDefinitionId,
            @RequestBody CaseDefinitionMigrationRequest request,
            Authentication authentication) {
        return definitions.migrateHistoric(engineId, caseDefinitionId, request, authentication);
    }

    /** Flowable 7.x only: re-point the history of one ended case. */
    @PostMapping("/cases/{engineId}/{caseInstanceId}/migrate-historic")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public ActionResult migrateHistoricCase(
            @PathVariable String engineId,
            @PathVariable String caseInstanceId,
            @RequestBody CaseDefinitionMigrationRequest request,
            Authentication authentication) {
        return definitions.migrateHistoricCase(engineId, caseInstanceId, request, authentication);
    }
}
