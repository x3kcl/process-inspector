package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * One ledger row of {@code GET /api/incidents} (R-BAU-10, INCIDENT-LEDGER.md §6) — a persisted
 * failure class's identity + live lifecycle state. Also embedded verbatim in
 * {@link IncidentDetail} so the list card and the detail header render from one shape.
 *
 * <p>{@code currentGeneration} is computed against {@code ErrorSignatureNormalizer.ALGO_VERSION}
 * at read time so the UI can split "current" from "archived generation" incidents without
 * hardcoding the normalizer version (R-SEM-03 needs-re-binding doctrine). {@code quiet} is
 * DERIVED per read ({@code lastSeen < now − inspector.incidents.quiet-window}) and never stored.
 *
 * <p>Scope honesty (R-SAFE-17, the {@code LeakDefinitionCount} doctrine — recompute, never
 * null): under read-scope enforcement {@code countsByEngine} is narrowed to the caller's
 * readable engines. Fully in scope ⇒ the stored fleet {@code lastTotal} is carried verbatim and
 * {@code partial=false}. Partially in scope ⇒ {@code lastTotal} is honestly RECOMPUTED as the
 * sum of the surviving engines' counts and {@code partial=true} — the fleet-wide total is never
 * presented as if it were the scoped total. {@code lastTruncated} stays the fleet observation
 * either way (a conservative floor marker: the truncated engine may be out of scope, but
 * "lower bound" can never overstate). A zero-intersection incident is omitted entirely.
 *
 * <p>{@code NON_NULL} so nullable fields ({@code exceptionClass}, {@code lastRegressedAt}) are
 * OMITTED on the wire, matching the generated contract's optionality (W3-2 doctrine).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentSummary(
        long id,
        String signatureHash,
        int algoVersion,
        boolean currentGeneration,
        String exceptionClass, // null when no stacktrace could refine the group
        String normalizedMessage,
        String sampleRawMessage, // one REAL member message — the same string Stage 0 renders
        String state, // OPEN | RESOLVED | REGRESSED (IncidentState verbatim)
        Instant firstSeen,
        Instant lastSeen,
        boolean quiet,
        long lastTotal, // lower bound when lastTruncated (R-SEM-12); scoped sum when partial
        boolean lastTruncated,
        Map<String, Map<String, Long>> countsByEngine, // engineId → "defKey:vN" → count
        boolean partial, // true iff the breakdown was narrowed by scope projection
        int regressionCount,
        Instant lastRegressedAt) {}
