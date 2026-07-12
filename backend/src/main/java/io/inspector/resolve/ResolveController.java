package io.inspector.resolve;

import io.inspector.dto.ResolveResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/resolve?q= — the omnibox endpoint (SPEC §4, R-SEM-04). Read-only fan-out;
 * VIEWER floor on any scope (the response only contains engines the resolver reached —
 * per-engine data still sits behind the detail endpoints' per-engine gates).
 */
@RestController
@RequestMapping("/api/resolve")
public class ResolveController {

    private final ResolveService resolveService;

    public ResolveController(ResolveService resolveService) {
        this.resolveService = resolveService;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public ResolveResponse resolve(@RequestParam("q") String q) {
        return resolveService.resolve(q);
    }
}
