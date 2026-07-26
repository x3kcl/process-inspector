package io.inspector.resolve;

import io.inspector.dto.ResolveResponse;
import io.inspector.security.ReadScopeGate;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/resolve?q= — the omnibox endpoint (SPEC §4, R-SEM-04). Read-only fan-out; VIEWER
 * floor on any scope. Scope-filtered exactly like {@link io.inspector.api.SearchController} and
 * {@link io.inspector.api.PersonTaskSearchController} (S2, R-SAFE-17): {@code readableEngineIds}
 * is resolved on the REQUEST thread and threaded into {@link ResolveService}, never computed
 * inside the fan-out (virtual threads spawned via {@code MdcPropagatingExecutors} do not inherit
 * the {@code SecurityContext}). An implicit "all engines" query narrows to the readable set
 * SILENTLY; an explicit {@code engine:id} composite naming an engine outside the caller's scope
 * gets a labeled {@code EngineProbe.outOfScope} entry instead of a silent "not found" — per-engine
 * match data (business key, timestamps, status) still only ever comes from an engine the caller
 * may read.
 */
@RestController
@RequestMapping("/api/resolve")
public class ResolveController {

    private final ResolveService resolveService;
    private final ReadScopeGate readScope;

    public ResolveController(ResolveService resolveService, ReadScopeGate readScope) {
        this.resolveService = resolveService;
        this.readScope = readScope;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public ResolveResponse resolve(@RequestParam("q") String q, Authentication authentication) {
        // S2 (R-SAFE-17): resolved on the request thread, never inside the fan-out.
        Set<String> readable = readScope.readableEngineIds(authentication);
        return resolveService.resolve(q, readable);
    }
}
