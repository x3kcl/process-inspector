package io.inspector.api;

import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.MeDto;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me} — the auth hint the SPA greys actions with. Answers from the
 * resolved scope grants (never from raw group names), one highest-role entry per
 * registered engine. Any authenticated user may ask who they are.
 */
@RestController
@RequestMapping("/api")
public class MeController {

    /** Ladder top-down: the first grant that covers the engine wins. */
    private static final Role[] LADDER_DESC = {Role.ADMIN, Role.OPERATOR, Role.RESPONDER, Role.VIEWER};

    private final EngineRegistry registry;
    private final RbacAuthorizer rbac;

    public MeController(EngineRegistry registry, RbacAuthorizer rbac) {
        this.registry = registry;
        this.rbac = rbac;
    }

    @GetMapping("/me")
    public MeDto me(Authentication authentication) {
        Map<String, String> engineRoles = new LinkedHashMap<>();
        Role highestAnywhere = null;
        for (EngineConfig engine : registry.all()) {
            for (Role candidate : LADDER_DESC) {
                if (rbac.hasRoleOn(authentication, candidate, engine.id())) {
                    engineRoles.put(engine.id(), candidate.name());
                    if (highestAnywhere == null || candidate.atLeast(highestAnywhere)) {
                        highestAnywhere = candidate;
                    }
                    break;
                }
            }
        }
        return new MeDto(
                authentication.getName(),
                highestAnywhere != null ? highestAnywhere.name() : null,
                engineRoles,
                rbac.canAdministerRegistry(authentication));
    }
}
