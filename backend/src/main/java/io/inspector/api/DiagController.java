package io.inspector.api;

import io.inspector.diag.DiagService;
import io.inspector.dto.DiagResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/diag} (issue #96, OPERATIONS.md §2 / RUNBOOK.md) — the operator's "what's going
 * on right now" snapshot: breaker states, cache ages, bulk-dispatch permit saturation, and the
 * last few engine-call failures with their correlationIds. The door check is the same coarse
 * "ADMIN somewhere" gate {@link io.inspector.api.AuditController#operationsLog} uses for the
 * cross-engine operations log; {@link DiagService} then filters every per-engine section down to
 * engines the caller actually holds ADMIN on, so a per-engine-scoped ADMIN never sees another
 * engine's breaker/permit/error data through this fleet-wide view.
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
    public DiagResponse diag(Authentication authentication) {
        return service.diag(authentication);
    }
}
