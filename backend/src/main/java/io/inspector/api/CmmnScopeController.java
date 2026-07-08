package io.inspector.api;

import io.inspector.cmmn.CmmnScopeService;
import io.inspector.dto.OutOfScopeDeadLetters;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Case Inspector Phase 1 (R-SEM-20): the drill-down behind the Stage-0
 * {@code outOfScopeDeadletters} count. Read-only, VIEWER floor, capability-gated (Flowable
 * ≥ 6.8): a pre-6.8 engine is refused with a ProblemDetail in {@link CmmnScopeService}, never
 * a silently wrong view. Lives under {@code /api/triage} because it is the Stage-0 landing's
 * CMMN reconciliation drill, not a per-instance detail.
 */
@RestController
@RequestMapping("/api/triage/engines/{engineId}")
public class CmmnScopeController {

    private final CmmnScopeService cmmnScope;

    public CmmnScopeController(CmmnScopeService cmmnScope) {
        this.cmmnScope = cmmnScope;
    }

    @GetMapping("/out-of-scope-deadletters")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public OutOfScopeDeadLetters outOfScopeDeadLetters(@PathVariable String engineId) {
        return cmmnScope.outOfScopeDeadLetters(engineId);
    }
}
