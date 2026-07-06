package io.inspector.api;

import io.inspector.action.ActionRequest;
import io.inspector.action.ActionResult;
import io.inspector.action.ActionVerb;
import io.inspector.action.CorrectiveActionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The verb-catalog endpoints (ARCH §4): one whitelisted route per target kind — never a
 * generic proxy. {@code @PreAuthorize} enforces the RBAC tier at the door (unknown verbs
 * pass through it to die as 404 here, not as a misleading 403); the service re-checks
 * scoped RBAC plus every other guard-ladder rail before anything touches an engine.
 */
@RestController
@RequestMapping("/api")
public class CorrectiveActionController {

    private final CorrectiveActionService actions;

    public CorrectiveActionController(CorrectiveActionService actions) {
        this.actions = actions;
    }

    @PostMapping("/instances/{engineId}/{instanceId}/actions/{verb}")
    @PreAuthorize("@rbac.canExecute(authentication, #engineId, #verb)")
    public ActionResult instanceAction(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @PathVariable String verb,
            @RequestBody(required = false) ActionRequest request,
            Authentication authentication) {
        return actions.execute(
                engineId,
                instanceId,
                resolve(verb, ActionVerb.TargetKind.INSTANCE),
                request != null ? request : ActionRequest.empty(),
                authentication);
    }

    @PostMapping("/definitions/{engineId}/{definitionId}/actions/{verb}")
    @PreAuthorize("@rbac.canExecute(authentication, #engineId, #verb)")
    public ActionResult definitionAction(
            @PathVariable String engineId,
            @PathVariable String definitionId,
            @PathVariable String verb,
            @RequestBody(required = false) ActionRequest request,
            Authentication authentication) {
        return actions.execute(
                engineId,
                definitionId,
                resolve(verb, ActionVerb.TargetKind.DEFINITION),
                request != null ? request : ActionRequest.empty(),
                authentication);
    }

    private static ActionVerb resolve(String path, ActionVerb.TargetKind expectedKind) {
        ActionVerb verb = ActionVerb.fromPath(path)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown verb: " + path));
        if (verb.targetKind() != expectedKind) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Verb '" + path + "' targets a " + verb.targetKind().name().toLowerCase() + ", not a "
                            + expectedKind.name().toLowerCase());
        }
        return verb;
    }
}
