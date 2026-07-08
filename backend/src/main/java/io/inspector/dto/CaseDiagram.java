package io.inspector.dto;

import java.util.List;

/**
 * The CMMN case diagram (Case Inspector Phase 2) — the raw CMMN 1.1 XML for a {@code cmmn-js}
 * {@code NavigatedViewer} plus the plan-item marker id sets, the polymorphic sibling of the BPMN
 * {@code InstanceDiagram}.
 *
 * <p>{@code cmmn-js} needs the {@code <cmmndi:CMMNDI>} block to render — a DI-less model imports
 * to an empty canvas (spike Q5). {@code graphicalNotationDefined} carries the case definition's
 * flag so the UI degrades to an explicit "no graphical layout" state rather than a blank box.
 *
 * <p>The marker id sets are keyed by the plan item's {@code elementId} (the CMMN DI
 * {@code cmmnElementRef} shape key) — NOT a job row's {@code elementId}, which is the plan-item
 * DEFINITION id (spike Q7). Empty for an ended case (no runtime plan items).
 */
public record CaseDiagram(
        String xml,
        boolean graphicalNotationDefined,
        List<String> activePlanItemElementIds,
        List<String> failedPlanItemElementIds) {}
