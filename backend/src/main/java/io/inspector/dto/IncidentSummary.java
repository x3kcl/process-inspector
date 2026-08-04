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
 * <p>{@code selfHeal} (RETRYING-RISK-LANE.md, #351) is the RETRYING risk lane's per-class
 * statistic — informational only (hard rail: never gates or reorders any corrective-action
 * rail), derived entirely from the BFF's own ledger + audit stores ({@code
 * io.inspector.selfheal.SelfHealStatsService}), zero new engine calls. Present on EVERY
 * incident (not just RETRYING ones — the class's tendency to self-heal is meaningful history
 * regardless of its current live state); {@code lane = INSUFFICIENT_HISTORY} is the expected,
 * common-for-a-long-time answer (measured 2026-08-04, R2-SELFHEAL-BASELINE-2026-08.md).
 *
 * <p>{@code attention} (ALARM-COST-MODEL.md §4, #353) is the cost-aware attention score, present
 * only while {@code inspector.triage.attention-ordering} is on. The ledger list keeps its SERVER
 * ordering ({@code lastSeen DESC} — the #308 hard cap must drop the OLDEST rows, so the store
 * fetch order is load-bearing) and its section doctrine (REGRESSED → OPEN → QUIET → RESOLVED,
 * derived client-side per INCIDENT-LEDGER §6/§8); the score orders WITHIN the live sections only,
 * where those sections are actually formed. Ordering only — never hides, never changes section
 * membership.
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
        Instant lastRegressedAt,
        SelfHealStats selfHeal,
        AttentionScore attention) {}
