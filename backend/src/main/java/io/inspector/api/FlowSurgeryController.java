package io.inspector.api;

import io.inspector.action.ActionResult;
import io.inspector.surgery.ChangeStatePreview;
import io.inspector.surgery.ChangeStateRequest;
import io.inspector.surgery.FlowSurgeryService;
import io.inspector.surgery.RestartInstanceRequest;
import io.inspector.surgery.RestartInstanceResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The v1.1 flow-surgery routes (SPEC §5 tier 2) — three explicit whitelisted paths, never
 * a generic proxy. Preview is a BFF simulation (Flowable has no change-state dry-run): it
 * runs every blocking guard and returns the EXACT REST body execute would send. The door
 * check is the tier-2 OPERATOR floor; the service re-checks scoped RBAC including the
 * ADMIN-on-prod rule for change-state (ARCH §5) plus the full guard ladder.
 */
@RestController
@RequestMapping("/api")
public class FlowSurgeryController {

    private final FlowSurgeryService surgery;

    public FlowSurgeryController(FlowSurgeryService surgery) {
        this.surgery = surgery;
    }

    @PostMapping("/instances/{engineId}/{instanceId}/change-state/preview")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'OPERATOR', #engineId)")
    public ChangeStatePreview previewChangeState(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @RequestBody ChangeStateRequest request,
            Authentication authentication) {
        return surgery.previewChangeState(engineId, instanceId, request, authentication);
    }

    @PostMapping("/instances/{engineId}/{instanceId}/change-state/execute")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'OPERATOR', #engineId)")
    public ActionResult executeChangeState(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @RequestBody ChangeStateRequest request,
            Authentication authentication) {
        return surgery.executeChangeState(engineId, instanceId, request, authentication);
    }

    @PostMapping("/instances/{engineId}/{instanceId}/restart")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'OPERATOR', #engineId)")
    public RestartInstanceResult restart(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @RequestBody(required = false) RestartInstanceRequest request,
            Authentication authentication) {
        return surgery.restartAsNew(
                engineId,
                instanceId,
                request != null ? request : new RestartInstanceRequest(null, null, null),
                authentication);
    }
}
