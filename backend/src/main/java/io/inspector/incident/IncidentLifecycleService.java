package io.inspector.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.GuardRefusedException;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.dto.AcknowledgeErrorGroupRequest;
import io.inspector.dto.IncidentResolution;
import io.inspector.dto.IncidentSummary;
import io.inspector.dto.ReopenIncidentRequest;
import io.inspector.dto.ResolveIncidentRequest;
import io.inspector.triage.ErrorGroupAckService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Incident Ledger's S3 lifecycle verbs (R-BAU-10, INCIDENT-LEDGER.md §6): resolve and
 * reopen. Both are <b>config-events, not corrective actions</b> — exactly the R-BAU-01
 * acknowledge's class of endpoint: a BFF-store-only claim about a triage surface, ZERO engine
 * calls, so none of the corrective-action rails that exist to protect engine state (typed
 * confirmation, capability gates, CAS restatement) apply — while the rails that protect the
 * OPERATION do, in the ack door's exact shape: OPERATOR floor at the controller, reason ≥10
 * always, fail-closed R-AUD-10 config-event audit with store compensation on audit failure
 * (503, the change was NOT applied), and no auto-retry anywhere.
 *
 * <p><b>RBAC asymmetry (deliberate):</b> resolve/reopen need plain OPERATOR — no per-engine
 * re-check — because they are FLEET-WIDE ledger claims about a failure class, not mutations of
 * (or mutes on) any engine's surface. The opt-in {@code alsoAcknowledge} composes the EXISTING
 * ack door, which keeps ITS stricter rule: OPERATOR on EVERY engine the group is live on
 * (SPEC §4 Stage 0 R-BAU-01 — muting a card hides it from everyone triaging those engines).
 * A caller who can resolve but lacks a grant on some involved engine gets a successful resolve
 * plus a reported-not-hidden acknowledge refusal.
 *
 * <p><b>Partial-ack honesty:</b> the acknowledge is a SECOND, separately-audited action invoked
 * strictly AFTER the resolve committed and audited. Its failure — any failure — never rolls the
 * resolve back; the outcome is reported per slice in the response body instead (see
 * {@link IncidentResolution}). The ack door itself is atomic across slices by design, so the
 * per-slice rows share one outcome.
 *
 * <p><b>Concurrency:</b> every transition is a state-conditional native UPDATE bumping
 * {@code version} (the S1 doctrine) — a race with the sampler or another operator makes the
 * write MISS, answered as a retryable 409 ProblemDetail ({@code incident-state-conflict}),
 * never a clobber and never a 500. Scope: an incident whose engines all sit outside the
 * caller's read scope answers the SAME 404 as an unknown id ({@link IncidentQueryService}
 * projection) — a scoped OPERATOR cannot mutate, or probe the existence of, what they cannot
 * see.
 */
@Service
public class IncidentLifecycleService {

    static final String ACTION_RESOLVE = "incident-resolve";
    static final String ACTION_REOPEN = "incident-reopen";

    private static final Logger log = LoggerFactory.getLogger(IncidentLifecycleService.class);
    private static final TypeReference<Map<String, Map<String, Long>>> COUNTS_SHAPE = new TypeReference<>() {};

    private final IncidentRepository incidents;
    private final IncidentEpisodeRepository episodes;
    private final IncidentQueryService query;
    private final ErrorGroupAckService acks;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public IncidentLifecycleService(
            IncidentRepository incidents,
            IncidentEpisodeRepository episodes,
            IncidentQueryService query,
            ErrorGroupAckService acks,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.incidents = incidents;
        this.episodes = episodes;
        this.query = query;
        this.acks = acks;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /* ---------------- resolve ---------------- */

    /**
     * OPEN/REGRESSED → RESOLVED (INCIDENT-LEDGER §6/§3.2): closes the live episode with the
     * resolve metadata, resets the zero-state flag so the regression gate arms FRESH, writes the
     * fail-closed config event, then — opt-in — composes the existing acknowledge door as a
     * second, separately-audited action whose per-slice outcome is reported, never rolled into
     * the resolve's fate.
     */
    public IncidentResolution resolve(long id, ResolveIncidentRequest request, Authentication auth) {
        String reason = requireReason(request.reason());
        String ticketId = normalizeTicket(request.ticketId());
        Incident row = loadVisible(id, auth);
        IncidentState from = row.getState();
        if (from == IncidentState.RESOLVED) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "incident-already-resolved",
                    "This incident is already RESOLVED — refresh the ledger. Nothing happened.");
        }
        IncidentEpisode live = episodes.findFirstByIncidentIdAndEndedAtIsNullOrderByStartedAtDesc(id)
                .orElse(null);
        if (incidents.transitionToResolved(id, from.name()) != 1) {
            throw stateConflict();
        }
        Long episodeId = null;
        if (live != null) {
            episodes.closeEpisode(live.getId(), clock.instant(), auth.getName(), reason, ticketId);
            episodeId = live.getId();
        } else {
            // Invariant drift (exactly one live episode per non-RESOLVED incident): the state
            // claim still stands and the audit row carries the resolve metadata — warn, not 500.
            log.warn("incident {} resolved without a live episode — resolve metadata carried by audit only", id);
        }
        IncidentEpisode closed = live;
        auditOrCompensate(
                ACTION_RESOLVE,
                auth.getName(),
                reason,
                transitionPayload(row, from, IncidentState.RESOLVED, reason, ticketId, episodeId),
                () -> {
                    if (closed != null) {
                        episodes.reopenEpisode(closed.getId());
                    }
                    incidents.revertResolve(id, from.name());
                });
        List<IncidentResolution.AckSliceOutcome> ackOutcomes =
                Boolean.TRUE.equals(request.alsoAcknowledge()) ? alsoAcknowledge(row, reason, ticketId, auth) : null;
        return new IncidentResolution(freshSummary(id, auth), ackOutcomes);
    }

    /* ---------------- reopen ---------------- */

    /**
     * RESOLVED → OPEN, the human undo (INCIDENT-LEDGER §3.2) — distinct from the automatic
     * REGRESSED transition: the LAST episode goes live again (ended_at cleared, resolve metadata
     * wiped) instead of a new one being minted, and {@code regression_count} is NOT incremented.
     * Reason required (audit-doctrine deviation from the body-less design row, flagged in §6).
     */
    public IncidentSummary reopen(long id, ReopenIncidentRequest request, Authentication auth) {
        String reason = requireReason(request.reason());
        Incident row = loadVisible(id, auth);
        if (row.getState() != IncidentState.RESOLVED) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "incident-not-resolved",
                    "Only a RESOLVED incident can be reopened — this one is " + row.getState() + ". Nothing happened.");
        }
        boolean previousSeenZero = row.isSeenZeroSinceResolve();
        IncidentEpisode last =
                episodes.findFirstByIncidentIdOrderByStartedAtDesc(id).orElse(null);
        IncidentEpisode toReopen = last != null && last.getEndedAt() != null ? last : null;
        if (incidents.transitionToReopened(id) != 1) {
            throw stateConflict();
        }
        Long episodeId = null;
        if (toReopen != null) {
            episodes.reopenEpisode(toReopen.getId());
            episodeId = toReopen.getId();
        } else {
            log.warn("incident {} reopened without a closed last episode — state claim stands, audited", id);
        }
        auditOrCompensate(
                ACTION_REOPEN,
                auth.getName(),
                reason,
                transitionPayload(row, IncidentState.RESOLVED, IncidentState.OPEN, reason, null, episodeId),
                () -> {
                    if (toReopen != null) {
                        // the entity was loaded before the native reopen — it still carries the
                        // closed-episode values, so the restore is byte-exact
                        episodes.closeEpisode(
                                toReopen.getId(),
                                toReopen.getEndedAt(),
                                toReopen.getResolvedBy(),
                                toReopen.getResolveReason(),
                                toReopen.getTicketId());
                    }
                    incidents.revertReopen(id, previousSeenZero);
                });
        return freshSummary(id, auth);
    }

    /* ---------------- the opt-in second action ---------------- */

    /**
     * Composes the EXISTING R-BAU-01 acknowledge door exactly as its controller does — one
     * {@link ErrorGroupAckService#acknowledge} call with the incident's coordinates and the
     * same reason/ticket (the service resolves live slices, re-checks OPERATOR per engine,
     * writes its OWN separately-audited config event). Any refusal is caught and reported per
     * slice of the incident's last observed breakdown; the resolve is never rolled back.
     */
    private List<IncidentResolution.AckSliceOutcome> alsoAcknowledge(
            Incident row, String reason, String ticketId, Authentication auth) {
        List<String[]> slices = breakdownSlices(row);
        try {
            acks.acknowledge(
                    new AcknowledgeErrorGroupRequest(
                            row.getSignatureHash(), row.getAlgoVersion(), reason, ticketId, null),
                    auth);
            return outcomes(slices, true, null, null);
        } catch (GuardRefusedException e) {
            // rbac-denied on some engine, error-group-absent (already drained), algo mismatch, …
            return outcomes(slices, false, e.code(), e.getMessage());
        } catch (ResponseStatusException e) {
            // the ack door's own fail-closed 503: ITS store change was compensated, ours stands
            return outcomes(slices, false, "audit-unavailable", e.getReason());
        } catch (RuntimeException e) {
            log.warn("also-acknowledge after resolving incident {} failed unexpectedly: {}", row.getId(), e.toString());
            return outcomes(
                    slices,
                    false,
                    "ack-failed",
                    "The acknowledge could not be completed — the incident is still resolved;"
                            + " acknowledge from the triage landing if needed.");
        }
    }

    /**
     * The incident's last observed engine × definitionKey slices, folded out of the
     * {@code countsByEngine} blob ({@code engineId → "defKey:vN" → count}, versions collapsed,
     * zero-filled versions skipped) — the reporting granularity of the opt-in acknowledge.
     */
    private List<String[]> breakdownSlices(Incident row) {
        Map<String, Map<String, Long>> counts;
        try {
            // NOT-NULL in V18, but readValue(null) would throw IAE past the catch below — and the
            // contract here is degrade-to-empty, never fail the already-committed resolve.
            String blob = row.getCountsByEngine();
            Map<String, Map<String, Long>> parsed = blob != null ? json.readValue(blob, COUNTS_SHAPE) : null;
            counts = parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException e) {
            counts = Map.of(); // corrupted display blob: the ack outcome list degrades to empty
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> slices = new ArrayList<>();
        counts.forEach((engineId, byDefVersion) -> byDefVersion.forEach((defVersionKey, count) -> {
            if (count == null || count == 0) {
                return; // a zero-filled sibling version is not an acknowledgeable slice
            }
            String definitionKey = definitionKeyOf(defVersionKey);
            if (seen.add(engineId + '\0' + definitionKey)) {
                slices.add(new String[] {engineId, definitionKey});
            }
        }));
        return slices;
    }

    /** Splits the aggregation's inner key {@code "defKey:vN"} (the ErrorGroupAckPolicy contract). */
    private static String definitionKeyOf(String defVersionKey) {
        int at = defVersionKey.lastIndexOf(':');
        if (at > 0 && defVersionKey.length() > at + 1 && defVersionKey.charAt(at + 1) == 'v') {
            return defVersionKey.substring(0, at);
        }
        return defVersionKey;
    }

    private static List<IncidentResolution.AckSliceOutcome> outcomes(
            List<String[]> slices, boolean acknowledged, String code, String message) {
        return slices.stream()
                .map(slice -> new IncidentResolution.AckSliceOutcome(slice[0], slice[1], acknowledged, code, message))
                .toList();
    }

    /* ---------------- guards & shared plumbing ---------------- */

    /** Unknown id and zero-scope-intersection answer the identical 404 (S2 doctrine, reused). */
    private Incident loadVisible(long id, Authentication auth) {
        Incident row = incidents.findById(id).orElseThrow(IncidentQueryService::notFound);
        if (query.projectForCaller(row, auth) == null) {
            throw IncidentQueryService.notFound();
        }
        return row;
    }

    /** The post-transition response shape: re-read, then the same scope projection as the reads. */
    private IncidentSummary freshSummary(long id, Authentication auth) {
        return incidents
                .findById(id)
                .map(fresh -> query.projectForCaller(fresh, auth))
                .orElse(null);
    }

    /** A state-conditional UPDATE missed: someone else moved the incident first. Retryable 409. */
    private static GuardRefusedException stateConflict() {
        return new GuardRefusedException(
                HttpStatus.CONFLICT,
                "incident-state-conflict",
                "The incident changed concurrently — reload the ledger and retry. Nothing happened.");
    }

    /** The ack door's reason gate verbatim (trimmed, ≥10 — INCIDENT-LEDGER §6). */
    private static String requireReason(String reason) {
        String clean = reason == null ? "" : reason.strip();
        if (clean.length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        return clean;
    }

    private static String normalizeTicket(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return null;
        }
        return ticketId.strip();
    }

    /**
     * The transition's audit payload (R-AUD-10, ack payload style): identity + from→to + the
     * episode the transition touched. The reason additionally rides the audit row's reason
     * COLUMN (the 5-arg {@code recordConfigEvent}, ack precedent) — it appears here too so the
     * payload is a self-contained transition record.
     */
    private static Map<String, Object> transitionPayload(
            Incident row, IncidentState from, IncidentState to, String reason, String ticketId, Long episodeId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", row.getId());
        payload.put("signatureHash", row.getSignatureHash());
        payload.put("algoVersion", row.getAlgoVersion());
        payload.put("stateFrom", from.name());
        payload.put("stateTo", to.name());
        payload.put("reason", reason);
        if (ticketId != null) {
            payload.put("ticketId", ticketId);
        }
        if (episodeId != null) {
            payload.put("episodeId", episodeId);
        }
        return payload;
    }

    /** Config-event audit, fail-closed (R-AUD-10): the {@code ErrorGroupAckService} discipline verbatim. */
    private void auditOrCompensate(
            String action, String actor, String reason, Map<String, Object> payload, Runnable compensate) {
        try {
            audit.recordConfigEvent(action, actor, true, reason, payload);
        } catch (AuditUnavailableException e) {
            compensate.run();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Refused fail-closed: the change was NOT applied because the audit store is unavailable",
                    e);
        }
    }
}
