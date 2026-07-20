package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One Stage 0 failure group: every dead-letter / RETRYING-tier job whose exception
 * normalizes to the same signature (SPEC §4 Stage 0, R-SEM-03). The hash is the binding
 * key for acknowledgments, annotations and playbooks; bindings persist {@code algoVersion}
 * so a normalizer bump flags them "needs re-binding" instead of silently re-binding.
 *
 * <p><b>Identity vs display since algo v2 (#270):</b> {@code signatureHash} and
 * {@code normalizedMessage} both come from the job row's own {@code exceptionMessage} — the
 * one input available for every group on every cycle. {@code exceptionClass} is a DISPLAY
 * refinement that may come from a representative stacktrace, so a card typically reads
 * "ArithmeticException — Error while evaluating expression: ${…}": the root-cause class
 * alongside the engine-reported wrapper message that actually keys the group. Do NOT assume
 * {@code hash == sha256(exceptionClass + "|" + normalizedMessage)} — that held under v1, and
 * is precisely what made a group's identity depend on a budget-bounded, fallible fetch.
 *
 * {@code countsByEngine} keys are {@code engineId → "defKey:vN" → count} — the inner key
 * carries the definition KEY because one root cause can span definitions. Versions of an
 * involved definition that produced zero failures are zero-filled: "v47: 312, v46: 0" is
 * the signal that a failure is version-specific (R-SEM-12 scope-explicit drill-through).
 * All counts are lower bounds whenever the engine's envelope carries a truncation marker.
 *
 * {@code acknowledgement} (R-BAU-01) is the render-time overlay from the BFF's own ack
 * store — null while unacknowledged, and ALWAYS null inside the cached aggregation (the
 * decorator joins it per request so ack state is live while engine data stays cached).
 *
 * <p>{@code NON_NULL} so absent fields are OMITTED on the wire, matching the generated
 * contract's optionality ({@code acknowledgement?:}) — a serialized {@code null} would
 * defeat every {@code !== undefined} check in the SPA (external review, W3-2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorGroup(
        String signatureHash,
        int algoVersion,
        String exceptionClass, // null when no stacktrace could refine the group
        String normalizedMessage,
        String sampleRawMessage, // one REAL member message, for display/debugging
        long total,
        // deadLetterCount / retryingCount are NULLABLE (not primitive) for one reason: under S2 read
        // scoping (R-SAFE-17) a group visible to a per-engine VIEWER may be only PARTIALLY in scope —
        // the DL/retrying split is a fleet-wide aggregate NOT broken down per engine, so it cannot be
        // honestly recomputed for a partial slice. The scope projector then nulls both (the recomputed
        // `total` + filtered `countsByEngine` stay truthful) and the UI shows "—" rather than a fabricated
        // or fleet-wide split. Non-null on every unscoped/fully-visible group. NON_NULL omits them on the
        // wire, matching the generated contract's `deadLetterCount?: number`.
        Long deadLetterCount,
        Long retryingCount, // failing-with-retries-left evidence (timer + executable lanes)
        Map<String, Map<String, Long>> countsByEngine,
        ErrorGroupAcknowledgement acknowledgement) {

    /** The aggregation-side shape: engine data only, never an ack overlay (see class doc). */
    public ErrorGroup(
            String signatureHash,
            int algoVersion,
            String exceptionClass,
            String normalizedMessage,
            String sampleRawMessage,
            long total,
            long deadLetterCount,
            long retryingCount,
            Map<String, Map<String, Long>> countsByEngine) {
        this(
                signatureHash,
                algoVersion,
                exceptionClass,
                normalizedMessage,
                sampleRawMessage,
                total,
                deadLetterCount,
                retryingCount,
                countsByEngine,
                null);
    }

    /** Render-time decoration: a NEW instance — the cached aggregation object is never mutated. */
    public ErrorGroup withAcknowledgement(ErrorGroupAcknowledgement info) {
        return new ErrorGroup(
                signatureHash,
                algoVersion,
                exceptionClass,
                normalizedMessage,
                sampleRawMessage,
                total,
                deadLetterCount,
                retryingCount,
                countsByEngine,
                info);
    }
}
