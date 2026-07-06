package io.inspector.dto;

import java.util.Map;

/**
 * One Stage 0 failure group: every dead-letter / RETRYING-tier job whose exception
 * normalizes to the same signature (SPEC §4 Stage 0, R-SEM-03). The hash is the binding
 * key for acknowledgments, annotations and playbooks; bindings persist {@code algoVersion}
 * so a normalizer bump flags them "needs re-binding" instead of silently re-binding.
 *
 * {@code countsByEngine} keys are {@code engineId → "defKey:vN" → count} — the inner key
 * carries the definition KEY because one root cause can span definitions. Versions of an
 * involved definition that produced zero failures are zero-filled: "v47: 312, v46: 0" is
 * the signal that a failure is version-specific (R-SEM-12 scope-explicit drill-through).
 * All counts are lower bounds whenever the engine's envelope carries a truncation marker.
 */
public record ErrorGroup(
        String signatureHash,
        int algoVersion,
        String exceptionClass, // null when no stacktrace could refine the group
        String normalizedMessage,
        String sampleRawMessage, // one REAL member message, for display/debugging
        long total,
        long deadLetterCount,
        long retryingCount, // failing-with-retries-left evidence (timer + executable lanes)
        Map<String, Map<String, Long>> countsByEngine) {}
