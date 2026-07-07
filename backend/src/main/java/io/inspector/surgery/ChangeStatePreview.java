package io.inspector.surgery;

import java.util.List;
import java.util.Map;

/**
 * The BFF-simulated change-state plan (SPEC §6 tier 2: plan-as-a-sentence + raw REST body
 * preview). Flowable exposes NO dry-run for change-state — this preview is the BFF's own
 * validation against live instance state and the deployed model, plus the EXACT request
 * body that execute will send. {@code simulationNote} states that honestly in UI copy.
 */
public record ChangeStatePreview(
        String engineId,
        String processInstanceId,
        String processDefinitionId,
        String method,
        String enginePath,
        Map<String, Object> payload,
        String summary,
        List<Warning> warnings,
        String simulationNote) {

    /** A non-blocking finding the operator should weigh before executing. */
    public record Warning(String code, String message) {}
}
