package io.inspector.audit;

/**
 * The narrow projection {@link AuditEntryRepository#findAttributableActionPoints} hands back —
 * the verb path and its outcome, nothing else. Same R-AUD-03 structural claim as
 * {@link RetryAuditPoint} (RETRYING-RISK-LANE.md §3.3/§10, issue #351): a constructor-expression
 * projection means {@code payload} is never in the SELECT list, so a matched row's (possibly
 * variable-bearing) payload JSON is never hydrated on this informational read path — the episode
 * action-attribution join (issue #358 item 2) reads only what {@link RetryAuditPoint} already
 * proved safe to read, widened from "engine + timestamp" to "engine + timestamp's verb +
 * outcome" (still no instance id, no actor, no reason, no payload).
 */
public record AttributedActionPoint(String action, AuditOutcome outcome) {}
