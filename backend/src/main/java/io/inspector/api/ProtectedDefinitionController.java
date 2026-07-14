package io.inspector.api;

import io.inspector.audit.ProtectedInstanceService;
import io.inspector.dto.ProtectionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * R-SAFE-05 definition-key write path (#172, deferred from #165's instance-scope-only shipment):
 * mark/unmark EVERY instance of a process-definition key protected, not just one composite ID.
 * Same rails as {@link ProtectedInstanceController}: ADMIN-per-engine floor, reason ≥10 either
 * way, fail-closed config-event audit, no auto-retry.
 */
@RestController
public class ProtectedDefinitionController {

    private final ProtectedInstanceService service;

    public ProtectedDefinitionController(ProtectedInstanceService service) {
        this.service = service;
    }

    @PostMapping("/api/definitions/{engineId}/{definitionKey}/protect")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void protect(
            @PathVariable String engineId,
            @PathVariable String definitionKey,
            @RequestBody ProtectionRequest body,
            Authentication authentication) {
        service.protectDefinition(authentication, engineId, definitionKey, body.reason());
    }

    @PostMapping("/api/definitions/{engineId}/{definitionKey}/unprotect")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unprotect(
            @PathVariable String engineId,
            @PathVariable String definitionKey,
            @RequestBody ProtectionRequest body,
            Authentication authentication) {
        service.unprotectDefinition(authentication, engineId, definitionKey, body.reason());
    }
}
