package io.inspector.spike.mcp;

import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ReadScopeGate;
import io.inspector.security.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SPIKE #492 S0 — NOT FOR MERGE. One read-only tool over existing reads (the engine list +
 * the caller's own grants), plus a probe tool whose only job is to show whether
 * {@code @PreAuthorize} on an {@code @McpTool} method is honoured. No new authorisation code:
 * the tool asks {@link RbacAuthorizer} and {@link ReadScopeGate} exactly as
 * {@code EnginesController.list} and {@code MeController.me} do.
 */
@Component
public class McpSpikeTools {

    private final RbacAuthorizer rbac;
    private final ReadScopeGate gate;
    private final EngineRegistry registry;

    public McpSpikeTools(RbacAuthorizer rbac, ReadScopeGate gate, EngineRegistry registry) {
        this.rbac = rbac;
        this.gate = gate;
        this.registry = registry;
    }

    /** Who am I, on which thread, and which engines may I read — the U2 probe. */
    @McpTool(
            name = "whoami_engines",
            description = "The signed-in user, the engines they may read and their role on each.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> whoamiEngines() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Thread thread = Thread.currentThread();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("thread", thread.getName());
        out.put("virtual", thread.isVirtual());
        out.put("principal", auth == null ? null : auth.getName());
        out.put("authType", auth == null ? null : auth.getClass().getSimpleName());
        // #758 role floor, same predicate as the fleet reads' @PreAuthorize("@rbac.atLeast(authentication,'VIEWER')").
        // A @RestControllerAdvice (NoEngineRoleAdvice) does NOT see tool exceptions, so the named code is
        // carried in the message here.
        if (!rbac.atLeast(auth, Role.VIEWER.name())) {
            throw new AccessDeniedException("no-engine-role: signed in, but no access has been granted yet");
        }
        Set<String> readable = gate.readableEngineIds(auth); // null = enforcement off
        Map<String, String> roles = new LinkedHashMap<>();
        for (var engine : registry.allForDisplay()) {
            if (readable != null && !readable.contains(engine.id())) {
                continue;
            }
            String best = null;
            for (Role r : List.of(Role.ADMIN, Role.OPERATOR, Role.RESPONDER, Role.VIEWER)) {
                if (rbac.hasRoleOn(auth, r, engine.id())) {
                    best = r.name();
                    break;
                }
            }
            roles.put(engine.id(), best);
        }
        out.put("scopeEnforced", gate.enforced());
        out.put("engines", roles);
        return out;
    }

    /**
     * The dangerous-failure-mode probe: an ADMIN-only expression on a tool method. If the MCP
     * invoker bypasses the Spring proxy, a VIEWER gets "secret" back and the spike test fails loudly.
     */
    @McpTool(name = "spike_admin_only", description = "Spike probe: ADMIN-only per @PreAuthorize.")
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public String adminOnly() {
        return "secret-admin-payload";
    }
}
