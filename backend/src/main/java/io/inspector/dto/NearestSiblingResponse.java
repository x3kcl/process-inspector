package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/nearest-sibling (SPEC §5.2) — the smart default for the
 * "compare with a sibling" affordance: the most recently COMPLETED instance of the SAME
 * definition version (same {@code processDefinitionId}). Resolved entirely over historic
 * queries — a completed sibling exists only in history — so nothing here touches a runtime
 * table (the successful siblings are, by definition, gone from runtime).
 *
 * <p>{@code candidates} is every non-self row of the same scan (endTime desc, capped at the
 * scan size), so the picker can offer a choice without a second engine query; {@code sibling}
 * is always its head. The manual id input remains the escape hatch for anything outside this
 * list (older runs, other definition versions).
 *
 * <p>"Successful" is the Flowable-honest sense: a {@code finished} historic instance reached an
 * end event, i.e. it drained instead of dead-lettering. {@code found=false} (with a null
 * {@code sibling} and empty {@code candidates}) is a first-class answer — a brand-new
 * definition version may have no completed run yet, and the UI falls back to the manual
 * disambiguation input.
 */
public record NearestSiblingResponse(
        boolean found,
        SiblingRef sibling, // null when found=false
        List<SiblingRef> candidates, // endTime desc, self excluded; empty when found=false
        int candidatesScanned,
        String definitionId,
        String processDefinitionKey,
        Integer definitionVersion) {

    /** The suggested sibling's identity + timing — enough for the picker, not the full diff. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SiblingRef(
            String processInstanceId, String businessKey, String startTime, String endTime, Long durationMs) {}

    /** No completed sibling of this definition version — offer the manual input instead. */
    public static NearestSiblingResponse none(
            int candidatesScanned, String definitionId, String processDefinitionKey, Integer definitionVersion) {
        return new NearestSiblingResponse(
                false, null, List.of(), candidatesScanned, definitionId, processDefinitionKey, definitionVersion);
    }
}
