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
 * R-SAFE-05 write path — the doors {@code CorrectiveActionService}/{@code BulkJobService}
 * already enforce against but that, until now, no production code ever called
 * ({@code ProtectedInstanceRepository.save()}/{@code .delete()} were test-setup-only). Both
 * BFF-store-only mutations under the corrective-actions rails: ADMIN-per-engine floor here,
 * reason ≥10 either way, fail-closed config-event audit, no auto-retry.
 */
@RestController
public class ProtectedInstanceController {

    private final ProtectedInstanceService service;

    public ProtectedInstanceController(ProtectedInstanceService service) {
        this.service = service;
    }

    @PostMapping("/api/instances/{engineId}/{instanceId}/protect")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void protect(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @RequestBody ProtectionRequest body,
            Authentication authentication) {
        service.protect(authentication, engineId, instanceId, body.reason());
    }

    @PostMapping("/api/instances/{engineId}/{instanceId}/unprotect")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unprotect(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @RequestBody ProtectionRequest body,
            Authentication authentication) {
        service.unprotect(authentication, engineId, instanceId, body.reason());
    }
}
