package io.inspector.triage;

import io.inspector.action.GuardRefusedException;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.AcknowledgeErrorGroupRequest;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.ErrorGroupAcknowledgement;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.UnacknowledgeErrorGroupRequest;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * R-BAU-01 error-group acknowledge — a BFF-only mutating verb under the full
 * corrective-actions rails, minus the engine call (there is none: acknowledging mutes a
 * triage card, never engine state — the honest alternative to the Suspend workaround the
 * baseline run predicted operators would reach for).
 *
 * <p>Rails, in door order: RBAC floor at the controller (OPERATOR), programmatic re-check
 * per slice ENGINE here (an OPERATOR on engine A must not mute a class that is also
 * failing on engine B they hold no grant on), reason ≥10 always, server-side baseline
 * resolution from the BFF's OWN aggregation (coordinates only cross the wire — mirror of
 * {@code BulkErrorClassService}), fail-closed config-event audit (R-AUD-10: on audit
 * failure the store change is COMPENSATED and the caller refused 503 — same pattern as
 * {@code SharedViewService}), and no auto-retry anywhere.
 *
 * <p>Cache interaction is deliberate (R-BAU-01): ack state is joined onto the dashboard at
 * RENDER time via {@link #decorate} — the 20s Caffeine engine cache is never busted and
 * never carries ack data, so an ack/unack is visible on the next read while engine fan-out
 * stays herd-protected. Baseline resolution reads the same cached aggregation the operator
 * is looking at — acknowledging attests the card that was on screen, not a fresher one.
 */
@Service
public class ErrorGroupAckService {

    static final String ACTION_ACK = "error-group-acknowledge";
    static final String ACTION_UNACK = "error-group-unacknowledge";

    private final ErrorGroupAckRepository repository;
    private final TriageService triage;
    private final AuditService audit;
    private final RbacAuthorizer rbac;
    private final Clock clock;
    private final int resurfaceThresholdPct;

    public ErrorGroupAckService(
            ErrorGroupAckRepository repository,
            TriageService triage,
            AuditService audit,
            RbacAuthorizer rbac,
            Clock clock,
            InspectorProperties props) {
        this.repository = repository;
        this.triage = triage;
        this.audit = audit;
        this.rbac = rbac;
        this.clock = clock;
        this.resurfaceThresholdPct = props.triageOrDefault().ackResurfaceThresholdPctOrDefault();
    }

    /* ---------------- the render-time join (read path) ---------------- */

    /**
     * Decorates the cached dashboard with live ack state — NEW objects only, the cached
     * aggregation is shared across requests and never mutated. Rows from another
     * normalizer generation ({@code algoVersion} mismatch) decorate nothing: their hashes
     * belong to a retired hash space ("needs re-binding", R-SEM-03).
     */
    public TriageDashboardResponse decorate(TriageDashboardResponse response) {
        List<ErrorGroupAck> all = repository.findAll();
        if (all.isEmpty()
                || response.errorGroups() == null
                || response.errorGroups().isEmpty()) {
            return response;
        }
        Map<String, List<ErrorGroupAck>> bySignature = new LinkedHashMap<>();
        for (ErrorGroupAck row : all) {
            bySignature
                    .computeIfAbsent(row.getSignatureHash(), k -> new ArrayList<>())
                    .add(row);
        }
        Instant now = clock.instant();
        List<ErrorGroup> decorated = response.errorGroups().stream()
                .map(group -> {
                    List<ErrorGroupAck> rows = bySignature.getOrDefault(group.signatureHash(), List.of()).stream()
                            .filter(row -> row.getAlgoVersion() == group.algoVersion())
                            .toList();
                    ErrorGroupAcknowledgement info =
                            ErrorGroupAckPolicy.evaluate(group, rows, resurfaceThresholdPct, now);
                    return info != null ? group.withAcknowledgement(info) : group;
                })
                .toList();
        return new TriageDashboardResponse(
                response.asOf(),
                response.engines(),
                response.statusCounts(),
                response.statusCountsByEngine(),
                decorated,
                response.perEngine());
    }

    /* ---------------- the mutation doors ---------------- */

    public ErrorGroupAcknowledgement acknowledge(AcknowledgeErrorGroupRequest request, Authentication auth) {
        requireCoordinates(request.signatureHash(), request.algoVersion());
        String reason = requireReason(request.reason());
        Instant expiresAt = parseExpiry(request.expiresAt());

        ErrorGroup group = requireLiveGroup(request.signatureHash());
        Map<ErrorGroupAckPolicy.SliceKey, ErrorGroupAckPolicy.Slice> slices = ErrorGroupAckPolicy.liveSlices(group);
        requireOperatorOnEveryEngine(
                auth,
                slices.keySet().stream()
                        .map(ErrorGroupAckPolicy.SliceKey::engineId)
                        .toList());

        Instant now = clock.instant();
        List<ErrorGroupAck> replaced =
                repository.findBySignatureHashAndAlgoVersion(group.signatureHash(), group.algoVersion());
        List<ErrorGroupAck> fresh = new ArrayList<>();
        for (Map.Entry<ErrorGroupAckPolicy.SliceKey, ErrorGroupAckPolicy.Slice> slice : slices.entrySet()) {
            fresh.add(new ErrorGroupAck(
                    group.signatureHash(),
                    group.algoVersion(),
                    slice.getKey().engineId(),
                    slice.getKey().definitionKey(),
                    auth.getName(),
                    reason,
                    normalizeTicket(request.ticketId()),
                    now,
                    expiresAt,
                    slice.getValue().count(),
                    slice.getValue().maxFailingVersion()));
        }
        repository.deleteAll(replaced);
        repository.flush();
        List<ErrorGroupAck> saved = repository.saveAll(fresh);
        repository.flush();

        auditOrCompensate(ACTION_ACK, auth.getName(), reason, ackPayload(group, fresh, expiresAt, request), () -> {
            repository.deleteAll(saved);
            repository.saveAll(
                    replaced.stream().map(ErrorGroupAck::detachedCopy).toList());
            repository.flush();
        });

        return ErrorGroupAckPolicy.evaluate(group, fresh, resurfaceThresholdPct, now);
    }

    public void unacknowledge(UnacknowledgeErrorGroupRequest request, Authentication auth) {
        requireCoordinates(request.signatureHash(), request.algoVersion());
        String reason = requireReason(request.reason());

        List<ErrorGroupAck> rows =
                repository.findBySignatureHashAndAlgoVersion(request.signatureHash(), request.algoVersion());
        if (rows.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "error-group-not-acknowledged",
                    "This error group holds no acknowledgment — refresh the triage landing. Nothing happened.");
        }
        requireOperatorOnEveryEngine(
                auth, rows.stream().map(ErrorGroupAck::getEngineId).toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("signatureHash", request.signatureHash());
        payload.put("algoVersion", request.algoVersion());
        payload.put("slices", sliceSummaries(rows));

        repository.deleteAll(rows);
        repository.flush();

        auditOrCompensate(ACTION_UNACK, auth.getName(), reason, payload, () -> {
            repository.saveAll(rows.stream().map(ErrorGroupAck::detachedCopy).toList());
            repository.flush();
        });
    }

    /* ---------------- guards ---------------- */

    private static void requireCoordinates(String signatureHash, Integer algoVersion) {
        if (signatureHash == null || signatureHash.isBlank()) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "error-class-signature-required", "signatureHash is required.");
        }
        if (algoVersion == null || algoVersion != ErrorSignatureNormalizer.ALGO_VERSION) {
            // Same refusal as the group retry: a hash from another normalizer generation
            // would silently match nothing (or the wrong group) — refuse loudly.
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "error-class-algo-mismatch",
                    "This card was rendered with signature algorithm v" + algoVersion + " but the BFF now computes v"
                            + ErrorSignatureNormalizer.ALGO_VERSION
                            + " — refresh the triage landing. Nothing happened.");
        }
    }

    private static String requireReason(String reason) {
        String clean = reason == null ? "" : reason.strip();
        if (clean.length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        return clean;
    }

    private Instant parseExpiry(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        Instant parsed;
        try {
            parsed = Instant.parse(expiresAt);
        } catch (DateTimeParseException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "ack-expiry-invalid",
                    "expiresAt must be an ISO-8601 instant (e.g. 2026-07-11T06:00:00Z).");
        }
        if (!parsed.isAfter(clock.instant())) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "ack-expiry-in-past",
                    "expiresAt is not in the future — an already-expired acknowledgment would mute nothing.");
        }
        return parsed;
    }

    /**
     * The group must be live on the SAME aggregation the operator is looking at (cached
     * read — deliberately: acknowledging attests the rendered card, and the engine cache
     * is never busted for a store-only write).
     */
    private ErrorGroup requireLiveGroup(String signatureHash) {
        TriageDashboardResponse dashboard = triage.dashboard(false);
        List<ErrorGroup> groups = dashboard.errorGroups() != null ? dashboard.errorGroups() : List.of();
        return groups.stream()
                .filter(g -> signatureHash.equals(g.signatureHash()))
                .findFirst()
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.CONFLICT,
                        "error-group-absent",
                        "No live failure group matches this signature — it has drained since the card"
                                + " rendered. Refresh the triage landing. Nothing happened."));
    }

    /** The controller door is the OPERATOR floor; this is the per-engine re-check (rails §2). */
    private void requireOperatorOnEveryEngine(Authentication auth, List<String> engineIds) {
        for (String engineId : new TreeSet<>(engineIds)) {
            if (!rbac.hasRoleOn(auth, Role.OPERATOR, engineId)) {
                throw new GuardRefusedException(
                        HttpStatus.FORBIDDEN,
                        "rbac-denied",
                        "Acknowledging this group needs OPERATOR on EVERY engine it is failing on —" + " missing on '"
                                + engineId + "'. Nothing happened.");
            }
        }
    }

    /* ---------------- audit ---------------- */

    private static Map<String, Object> ackPayload(
            ErrorGroup group, List<ErrorGroupAck> fresh, Instant expiresAt, AcknowledgeErrorGroupRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("signatureHash", group.signatureHash());
        payload.put("algoVersion", group.algoVersion());
        payload.put("exceptionClass", group.exceptionClass());
        payload.put(
                "acknowledgedTotal",
                fresh.stream().mapToLong(ErrorGroupAck::getAcknowledgedCount).sum());
        payload.put("slices", sliceSummaries(fresh));
        if (expiresAt != null) {
            payload.put("expiresAt", expiresAt.toString());
        }
        if (request.ticketId() != null && !request.ticketId().isBlank()) {
            payload.put("ticketId", request.ticketId().strip());
        }
        return payload;
    }

    /** {@code engineId · defKey:vMax · count} — one legible provenance line per slice. */
    private static List<String> sliceSummaries(List<ErrorGroupAck> rows) {
        return rows.stream()
                .map(row -> row.getEngineId() + " · " + row.getDefinitionKey()
                        + (row.getAcknowledgedMaxVersion() != null ? ":v" + row.getAcknowledgedMaxVersion() : "")
                        + " · " + row.getAcknowledgedCount())
                .toList();
    }

    private static String normalizeTicket(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return null;
        }
        return ticketId.strip();
    }

    /** Config-event audit, fail-closed (R-AUD-10): on failure, undo the store change and refuse 503. */
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
