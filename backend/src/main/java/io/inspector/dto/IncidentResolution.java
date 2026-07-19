package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The {@code POST /api/incidents/{id}/resolve} response (R-BAU-10 S3): the freshly-transitioned
 * incident in the SAME scope-projected {@link IncidentSummary} shape the list/detail serve, plus
 * — only when {@code alsoAcknowledge=true} was requested — the per-slice outcome of the second,
 * separately-audited R-BAU-01 acknowledge action.
 *
 * <p><b>Partial-failure honesty:</b> the resolve itself is NEVER rolled back by an acknowledge
 * failure — resolve is a ledger claim that already committed and audited before the ack was
 * attempted. The existing ack door is atomic across a signature's slices by design (its per-engine
 * OPERATOR re-check refuses the WHOLE acknowledge when any involved engine lacks a grant —
 * SPEC §4 Stage 0 R-BAU-01), so the slices listed here — one per (engine, definitionKey) in the
 * incident's last observed breakdown — always share one outcome: all {@code acknowledged=true},
 * or all {@code false} with the refusal's {@code code}/{@code message} so the operator knows the
 * resolve stood and exactly why the mute did not.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentResolution(IncidentSummary incident, List<AckSliceOutcome> acknowledgements) {

    /** One engine × definition slice of the opt-in acknowledge, with the shared outcome. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AckSliceOutcome(
            String engineId,
            String definitionKey,
            boolean acknowledged,
            String code, // the refusal's machine code (rbac-denied, error-group-absent, …) — null on success
            String message) {} // developer-authored refusal copy — null on success
}
