package io.inspector.audit;

import java.time.Instant;

/**
 * The narrow projection {@link AuditEntryRepository#findSuccessfulRetryJobPoints} hands back —
 * an engine id and an instant, and nothing else.
 *
 * <p>It exists to make a DOCUMENTED CLAIM literally true. The self-heal confound scan
 * (RETRYING-RISK-LANE.md §3.3/§10) only ever needed "did a successful retry land on an engine
 * this class touches, when" — engine + timestamp — and both the repository's javadoc and the
 * design's §10 said it read the audit rows "without touching the audit payload". Selecting the
 * ENTITY did touch it: {@code AuditEntry.payload} is a plain eager basic attribute (no
 * {@code @Basic(LAZY)}, no bytecode enhancement in this build), so every matched row's payload
 * JSON — which on an {@code audit-payload: full} engine carries process VARIABLES — was
 * hydrated into heap on a purely informational read path and then discarded unread. A
 * constructor-expression projection means the column is never in the SELECT list at all, so the
 * R-AUD-03 data-minimization claim holds by construction rather than by convention.
 */
public record RetryAuditPoint(String engineId, Instant ts) {}
