package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{instanceId}/explain-status — the falsifiable derivation
 * (R-L3-01, SPEC §3): every status chip can show WHY it is what it is. The evidence is
 * RE-DERIVED on demand (the inspector never retains the original response bytes) and labeled
 * as such: {@link #rederived} is always {@code true} and {@link #rederivedAt} carries the
 * derivation instant.
 *
 * <ul>
 *   <li>{@link #plan}/{@link #planReason} — the plan shape chosen and why (an ended instance
 *       short-circuits without scanning the failure lanes; an open one runs the full scan +
 *       a bounded call-activity descendant walk).</li>
 *   <li>{@link #legs} — each raw engine call the re-derivation made: method, URL, request
 *       body, HTTP status, wall duration, {@code asOf}, and its source ({@code live} — this
 *       path never serves cached bytes).</li>
 *   <li>{@link #findings} — per-flag provenance ("hasFailingJobs ⇐ timer/executable lane, job
 *       8123"; "failedInSubprocess ⇐ child instance …, dead-letter job …") with a deep-link
 *       target for the failing child's Errors &amp; Jobs.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusEvidence(
        String compositeId,
        String engineId,
        String processInstanceId,
        InstanceStatus status,
        InstanceStatusFlags flags,
        String plan, // ENDED_SHORT_CIRCUIT | LIVE_LANE_SCAN
        String planReason,
        List<Leg> legs,
        List<FlagFinding> findings,
        boolean rederived, // always true — this is never a replay of retained bytes
        String rederivedAt, // ISO instant the re-derivation completed
        String note) {

    /** One captured engine call. {@code status} null = no HTTP reply (transport error / open breaker). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Leg(
            String label,
            String method,
            String url,
            String requestBody,
            Integer status,
            long durationMs,
            String asOf,
            String source) {} // "live" — the per-instance derivation never serves cached bytes

    /** Per-flag provenance: which leg set it and the identifying detail (job / child id). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlagFinding(
            String flag,
            boolean value,
            String source, // the leg/label that decided this flag
            String detail, // human-readable "why": job id, endTime, child id, …
            String deepLinkInstanceId) {} // the failing child instance to jump to (failedInSubprocess)
}
