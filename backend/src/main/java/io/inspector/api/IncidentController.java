package io.inspector.api;

import io.inspector.dto.IncidentDetail;
import io.inspector.dto.IncidentResolution;
import io.inspector.dto.IncidentSummary;
import io.inspector.dto.ReopenIncidentRequest;
import io.inspector.dto.ResolveIncidentRequest;
import io.inspector.incident.IncidentLifecycleService;
import io.inspector.incident.IncidentQueryService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Incident Ledger doors (R-BAU-10, INCIDENT-LEDGER.md §6): the S2 reads (VIEWER floor, no
 * delete anywhere — the ledger is history) and the S3 lifecycle verbs (OPERATOR floor).
 *
 * <p>GET /api/incidents — the bounded ledger list, most-recently-seen first. Unpaginated in v1
 * BY DESIGN: cardinality is distinct failure classes (tens to hundreds), and the client derives
 * its sections (REGRESSED/OPEN/QUIET/RESOLVED + generation split) from the full list.
 * {@code state} filters case-insensitively (unknown ⇒ 400 ProblemDetail); {@code window}
 * (hours, clamped to 30 days like {@code /api/triage/trends}) keeps only incidents last seen
 * inside the window.
 *
 * <p>GET /api/incidents/{id} — the list item + full episode history + windowed occurrence
 * series + the live Stage-0 join ({@code window} hours, same clamp, default 24). Unknown id ⇒
 * 404 ProblemDetail; an incident entirely outside the caller's read scope answers the SAME 404
 * (existence is not leaked — deliberately not a 403).
 *
 * <p>POST /api/incidents/{id}/resolve · /reopen — the S3 lifecycle verbs: <b>config-events,
 * not corrective actions</b> (the R-BAU-01 acknowledge's endpoint class — a BFF-store-only
 * ledger claim, zero engine calls, so no typed confirmation/capability gate; but OPERATOR
 * floor, reason ≥10, fail-closed R-AUD-10 audit, no auto-retry — all enforced in
 * {@link IncidentLifecycleService}). Resolve needs plain OPERATOR fleet-wide; only the opt-in
 * {@code alsoAcknowledge} composition keeps the ack door's stricter per-engine re-check —
 * the deliberate asymmetry documented on the service. Both answer the read doors' 404 for
 * unknown AND out-of-scope ids, and a raced state transition as a retryable 409.
 *
 * <p>Every route is scope-projected per R-SAFE-17 via {@link IncidentQueryService} and reads
 * the ledger store alone — all stay live under {@code inspector.incidents.enabled=false} (that
 * flag gates INGESTION, not reads/lifecycle: already-written history remains inspectable and
 * claimable).
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentQueryService service;
    private final IncidentLifecycleService lifecycle;

    public IncidentController(IncidentQueryService service, IncidentLifecycleService lifecycle) {
        this.service = service;
        this.lifecycle = lifecycle;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public List<IncidentSummary> list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer window,
            Authentication auth) {
        return service.list(state, window, auth);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public IncidentDetail detail(
            @PathVariable long id, @RequestParam(defaultValue = "24") int window, Authentication auth) {
        return service.detail(id, window, auth);
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("@rbac.atLeast(authentication, 'OPERATOR')")
    public IncidentResolution resolveIncident(
            @PathVariable long id, @RequestBody ResolveIncidentRequest request, Authentication auth) {
        return lifecycle.resolve(id, request, auth);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("@rbac.atLeast(authentication, 'OPERATOR')")
    public IncidentSummary reopenIncident(
            @PathVariable long id, @RequestBody ReopenIncidentRequest request, Authentication auth) {
        return lifecycle.reopen(id, request, auth);
    }
}
