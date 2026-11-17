package io.inspector.api;

import io.inspector.dto.EngineDto;
import io.inspector.registry.EngineRegistry;
import io.inspector.registry.EngineSystemInfoService;
import io.inspector.registry.EngineSystemInfoService.EngineSystemInfo;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ReadScopeGate;
import io.inspector.security.Role;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/engines — the Stage 0 triage health strip. Always a 200 list: the registry
 * health map has an entry for every engine (unknown() at boot, unreachable(...) after
 * failures), so there is no exception path here by construction.
 *
 * <p>W1#4 (theme T6): the list is the DISPLAY surface — it includes non-active engines
 * (lifecycle {@code disabled}/{@code draft}/{@code probe_failed}) so the dashboard renders
 * them greyed-with-reason instead of silently omitting them (R-SEM-17/R-GOV-04). Fan-out
 * and mutation targets keep resolving through the enabled-only registry views.
 *
 * <p>#740 (R-SAFE-17): with read-scope enforcement on, the list carries only engines the caller may
 * read and a per-engine read of any other engine is refused with the method-security 403 every other
 * engine-scoped read answers. Off: unchanged. #747: the full system read needs ADMIN on THAT engine
 * with enforcement on AND off (the engine-agnostic {@code ROLE_ADMIN} no longer opens it).
 */
@RestController
@RequestMapping("/api/engines")
public class EnginesController {

    private final EngineRegistry registry;
    private final EngineSystemInfoService systemInfo;
    private final ReadScopeGate gate;
    private final RbacAuthorizer rbac;

    public EnginesController(
            EngineRegistry registry, EngineSystemInfoService systemInfo, ReadScopeGate gate, RbacAuthorizer rbac) {
        this.registry = registry;
        this.systemInfo = systemInfo;
        this.gate = gate;
        this.rbac = rbac;
    }

    @GetMapping
    public List<EngineDto> list(Authentication auth) {
        return registry.allForDisplay().stream()
                .filter(e -> readable(auth, e.id()))
                .map(e -> EngineDto.from(e, registry.healthOf(e.id())))
                .toList();
    }

    /** Version, properties, and table counts. A viewer gets the version line only (#509). */
    @GetMapping("/{id}/system")
    public EngineSystemInfo system(@PathVariable String id, Authentication auth) {
        if (!readable(auth, id)) {
            throw new AccessDeniedException("Access Denied"); // the @PreAuthorize refusal, verbatim
        }
        // #747: the full read is an admin surface — ADMIN on THIS engine, enforcement on or off.
        boolean admin = rbac.hasRoleOn(auth, Role.ADMIN, id);
        return systemInfo.load(id, admin || hasAuthority(auth, RbacAuthorizer.REGISTRY_ADMIN_AUTHORITY));
    }

    private boolean readable(Authentication auth, String engineId) {
        return !gate.enforced() || gate.canRead(auth, engineId);
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        return auth != null
                && auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(authority::equals);
    }
}
