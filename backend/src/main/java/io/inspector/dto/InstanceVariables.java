package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/variables — the TYPED variable ledger (SPEC §4,
 * R-UXQ-13): never a blind JSON map. Every row keeps the engine-declared {@code type}
 * verbatim next to a byte-capped typed {@code value} — the UI renders per-type widgets and
 * plain-language type chips from it, and an edit always re-fetches the full value first.
 *
 * <p>Scope segregation per SPEC §4: {@code processVariables} is "Case data (process
 * scope)"; execution-local rows group per execution node (multi-instance loop locals live
 * there), each scope naming the activity it sits on. A completed instance serves the
 * historic projection ({@code source=HISTORIC}) — runtime endpoints 404 after the end event.
 */
public record InstanceVariables(
        String source, // RUNTIME | HISTORIC
        List<VariableDto> processVariables,
        List<ExecutionScope> executionScopes) {

    /** Variables local to ONE execution node ("Step-local: Validate line item"). */
    public record ExecutionScope(
            String executionId,
            String activityId, // where that execution sits — the group header
            String parentExecutionId,
            List<VariableDto> variables) {}

    /**
     * One typed ledger row. {@code value} carries the engine's TYPED value (a number stays
     * a JSON number, a boolean a boolean — string-ifying is the banned anti-pattern) up to
     * the 256 KiB structured-preview cap; over the cap the row ships {@code value=null},
     * {@code truncated=true} and {@code sizeBytes}, and the full value is an on-demand
     * fetch ({@code GET …/variables/{name}}) — a multi-MiB blob must crash neither the
     * browser nor the engine (SPEC §4 size safeguards).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VariableDto(
            String name,
            String type, // engine-declared type, verbatim (glossary tooltip maps it)
            Object value,
            boolean truncated,
            Long sizeBytes, // serialized size, reported when measured (structured types)
            String scope, // global (process) | local (execution)
            String executionId, // owning execution for local rows
            String taskId) {} // historic task-scoped rows
}
