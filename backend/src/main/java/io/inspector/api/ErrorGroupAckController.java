package io.inspector.api;

import io.inspector.dto.AcknowledgeErrorGroupRequest;
import io.inspector.dto.ErrorGroupAcknowledgement;
import io.inspector.dto.UnacknowledgeErrorGroupRequest;
import io.inspector.triage.ErrorGroupAckService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * R-BAU-01 — the error-group acknowledge doors (SPEC §4 Stage 0 "Acknowledge"). Both are
 * BFF-store-only mutations under the corrective-actions rails: OPERATOR floor here (the
 * service re-checks OPERATOR per slice engine), reason ≥10, fail-closed config-event
 * audit, no auto-retry. Coordinates only cross the wire — baselines resolve server-side.
 */
@RestController
@RequestMapping("/api/triage/error-groups")
public class ErrorGroupAckController {

    private final ErrorGroupAckService service;

    public ErrorGroupAckController(ErrorGroupAckService service) {
        this.service = service;
    }

    @PostMapping("/acknowledge")
    @PreAuthorize("@rbac.atLeast(authentication, 'OPERATOR')")
    public ErrorGroupAcknowledgement acknowledge(
            @RequestBody AcknowledgeErrorGroupRequest request, Authentication auth) {
        return service.acknowledge(request, auth);
    }

    @PostMapping("/unacknowledge")
    @PreAuthorize("@rbac.atLeast(authentication, 'OPERATOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unacknowledge(@RequestBody UnacknowledgeErrorGroupRequest request, Authentication auth) {
        service.unacknowledge(request, auth);
    }
}
