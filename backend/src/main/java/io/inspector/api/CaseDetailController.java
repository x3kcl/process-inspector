package io.inspector.api;

import io.inspector.cmmn.CaseDetailService;
import io.inspector.dto.CaseDetail;
import io.inspector.dto.CaseDiagram;
import io.inspector.dto.CasePlanItems;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Case Inspector Phase 2 (R-SEM-20): the read-only, polymorphic Stage-2 detail of one CMMN case —
 * the CMMN sibling of {@code InstanceDetailController} ({@code /api/instances/…}). VIEWER floor;
 * capability-gated (Flowable ≥ 6.8) in {@link CaseDetailService}, so a pre-6.8 engine is refused
 * with a ProblemDetail, never a silently wrong page. No mutation — CMMN corrective actions are
 * Phase 3.
 */
@RestController
@RequestMapping("/api/cases/{engineId}/{caseInstanceId}")
public class CaseDetailController {

    private final CaseDetailService cases;

    public CaseDetailController(CaseDetailService cases) {
        this.cases = cases;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public CaseDetail vitals(@PathVariable String engineId, @PathVariable String caseInstanceId) {
        return cases.vitals(engineId, caseInstanceId);
    }

    @GetMapping("/diagram")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public CaseDiagram diagram(@PathVariable String engineId, @PathVariable String caseInstanceId) {
        return cases.diagram(engineId, caseInstanceId);
    }

    @GetMapping("/plan-items")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public CasePlanItems planItems(@PathVariable String engineId, @PathVariable String caseInstanceId) {
        return cases.planItems(engineId, caseInstanceId);
    }
}
