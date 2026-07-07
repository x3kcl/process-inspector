package io.inspector.surgery;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outcome of restart-as-new. {@code skippedVariables} is the honesty report: every
 * historic variable NOT carried over, with the reason (engine-intrinsic, non-portable
 * type) — a silent drop would masquerade as a faithful copy.
 */
public record RestartInstanceResult(
        UUID auditId,
        String correlationId,
        String outcome,
        Integer engineHttpStatus,
        String newProcessInstanceId,
        String processDefinitionId,
        List<String> carriedVariables,
        Map<String, String> skippedVariables,
        String deltaStatement) {}
