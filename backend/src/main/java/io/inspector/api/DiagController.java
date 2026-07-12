package io.inspector.api;

import io.inspector.diag.DiagService;
import io.inspector.dto.DiagResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/diag} (issue #96, OPERATIONS.md §2 / RUNBOOK.md) — the operator's "what's going
 * on right now" snapshot: breaker states, cache ages, bulk-dispatch permit saturation, and the
 * last few engine-call failures with their correlationIds. Global ADMIN, not per-engine — this is
 * fleet-wide BFF-process diagnostics, not an engine-scoped read.
 */
@RestController
@RequestMapping("/api/diag")
public class DiagController {

    private final DiagService service;

    public DiagController(DiagService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public DiagResponse diag() {
        return service.diag();
    }
}
