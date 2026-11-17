package io.inspector.api;

import io.inspector.action.ActionScope;
import io.inspector.detail.VariableRevealService;
import io.inspector.dto.RevealedVariable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audited reveal (#743, SPEC R-AUD-12, ARCH §4): one secret-named variable's value, POST only and
 * {@code no-store}, mirroring the single-variable read routes ({@code executionId} = step-local). The
 * door is the read's VIEWER floor on the engine so a refused tier is still audited; the OPERATOR tier,
 * read scope, audit and rate limit are enforced in {@link VariableRevealService}. Not an agent tool.
 */
@RestController
@RequestMapping("/api")
public class VariableRevealController {

    private final VariableRevealService reveals;

    public VariableRevealController(VariableRevealService reveals) {
        this.reveals = reveals;
    }

    /** Optional free-text reason; required (≥10 chars) on a prod engine. */
    public record RevealRequest(String reason) {}

    @PostMapping("/instances/{engineId}/{instanceId}/variables/{name}/reveal")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public ResponseEntity<RevealedVariable> revealInstanceVariable(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @PathVariable String name,
            @RequestParam(required = false) String executionId,
            @RequestBody(required = false) RevealRequest request,
            Authentication authentication) {
        return noStore(reveals.reveal(
                ActionScope.BPMN, engineId, instanceId, name, executionId, reason(request), authentication));
    }

    @PostMapping("/cases/{engineId}/{caseInstanceId}/variables/{name}/reveal")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public ResponseEntity<RevealedVariable> revealCaseVariable(
            @PathVariable String engineId,
            @PathVariable String caseInstanceId,
            @PathVariable String name,
            @RequestBody(required = false) RevealRequest request,
            Authentication authentication) {
        return noStore(reveals.reveal(
                ActionScope.CMMN, engineId, caseInstanceId, name, null, reason(request), authentication));
    }

    private static String reason(RevealRequest request) {
        return request == null ? null : request.reason();
    }

    private static ResponseEntity<RevealedVariable> noStore(RevealedVariable body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
