package io.inspector.api;

import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.MeDto;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.AbsoluteSessionTimeoutFilter;
import io.inspector.security.HttpHardeningProperties;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.security.reauth.DangerousActionReauthGate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final DangerousActionReauthGate reauth;
    private final HttpHardeningProperties hardening;
    private final Clock clock;

    public MeController(
            EngineRegistry registry,
            RbacAuthorizer rbac,
            DangerousActionReauthGate reauth,
            HttpHardeningProperties hardening,
            Clock clock) {
        this.registry = registry;
        this.rbac = rbac;
        this.reauth = reauth;
        this.hardening = hardening;
        this.clock = clock;
    }

    @GetMapping("/me")
    public MeDto me(Authentication authentication, HttpServletRequest request) {
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
                rbac.canAdministerRegistry(authentication),
                rbac.canAdministerAccess(authentication),
                rbac.isBreakGlass(authentication),
                reauth.hint(authentication),
                sessionExpiresAt(request));
    }

    /**
     * The absolute-cap guillotine instant (IDP-SECURITY.md §5, R-SAFE-07 warn-before-guillotine):
     * the session's {@link AbsoluteSessionTimeoutFilter#CREATED_AT} birth stamp plus the effective
     * cap — the per-session override when present (break-glass stamps 4 h), else the configured
     * default. On the very FIRST request the session is only created at response commit (the
     * security-context repository saves lazily), so the controller sees no session/stamp yet —
     * its birth IS "now", and now + cap is the exact answer. Presentation only: the filter stays
     * the enforcement; the SPA uses this for the countdown banner, never to gate.
     */
    private Instant sessionExpiresAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Duration cap = Duration.ofHours(hardening.sessionAbsoluteCapHoursOrDefault());
        Instant birth = clock.instant(); // new/unstamped session: born on this request
        if (session != null) {
            if (session.getAttribute(AbsoluteSessionTimeoutFilter.SESSION_CAP_MS_ATTR) instanceof Long capMs) {
                cap = Duration.ofMillis(capMs);
            }
            if (session.getAttribute(AbsoluteSessionTimeoutFilter.CREATED_AT) instanceof Long createdAtMs) {
                birth = Instant.ofEpochMilli(createdAtMs);
            }
        }
        return birth.plus(cap);
    }
}
