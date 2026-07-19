package io.inspector.api;

import io.inspector.dto.IncidentDetail;
import io.inspector.dto.IncidentSummary;
import io.inspector.incident.IncidentQueryService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Incident Ledger read doors (R-BAU-10 S2, INCIDENT-LEDGER.md §6) — read-only, VIEWER
 * floor, no delete anywhere (the ledger is history; lifecycle verbs arrive with S3).
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
 * <p>Both routes are scope-projected per R-SAFE-17 in {@link IncidentQueryService} and read the
 * ledger store alone — they stay live under {@code inspector.incidents.enabled=false} (that
 * flag gates INGESTION, not reads: already-written history remains inspectable).
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentQueryService service;

    public IncidentController(IncidentQueryService service) {
        this.service = service;
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
}
