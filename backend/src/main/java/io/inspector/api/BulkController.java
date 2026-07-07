package io.inspector.api;

import io.inspector.bulk.BulkDtos;
import io.inspector.bulk.BulkErrorClassService;
import io.inspector.bulk.BulkJobService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The M5 bulk surface (SPEC §7): submit / list / detail / cancel / verify-now. The door
 * gate is the coarse role floor; the service re-runs the FULL per-item guard chain (each
 * item is a normal single-target action with its own scoped-RBAC check and audit row).
 * Failures answer as the same ProblemDetails the single-target endpoints use
 * ({@link ActionExceptionHandler}).
 */
@RestController
@RequestMapping("/api/bulk")
public class BulkController {

    private final BulkJobService bulk;
    private final BulkErrorClassService errorClass;

    public BulkController(BulkJobService bulk, BulkErrorClassService errorClass) {
        this.bulk = bulk;
        this.errorClass = errorClass;
    }

    @PostMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'RESPONDER')")
    public BulkDtos.BulkJobDto submit(@RequestBody BulkDtos.BulkSubmitRequest request, Authentication authentication) {
        return bulk.submit(request, authentication);
    }

    /**
     * v1.x #1 — error-class group retry from the triage landing. The body carries the
     * group's COORDINATES only (signature + definition version, optionally one engine);
     * the BFF re-resolves the FAILED members server-side and feeds them into the same
     * persisted bulk machinery as {@link #submit}. Same door floor as the grid bulk —
     * this is an alternate entry to the identical retry-job fan-out.
     */
    @PostMapping("/error-class")
    @PreAuthorize("@rbac.atLeast(authentication, 'RESPONDER')")
    public BulkDtos.BulkJobDto submitErrorClass(
            @RequestBody BulkDtos.BulkErrorClassRequest request, Authentication authentication) {
        return errorClass.submit(request, authentication);
    }

    /** The operations drawer's hydration read — persisted jobs survive BFF restarts and browser refreshes. */
    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public List<BulkDtos.BulkJobDto> recent(@RequestParam(defaultValue = "20") int limit) {
        return bulk.recent(limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public BulkDtos.BulkJobDto get(@PathVariable UUID id) {
        return bulk.get(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@rbac.atLeast(authentication, 'RESPONDER')")
    public BulkDtos.BulkJobDto cancel(@PathVariable UUID id, Authentication authentication) {
        return bulk.cancel(id, authentication);
    }

    /** R-SAFE-09: precondition recheck of ONE unknown item — reads engine state, never re-fires. */
    @PostMapping("/{id}/items/{ordinal}/verify")
    @PreAuthorize("@rbac.atLeast(authentication, 'RESPONDER')")
    public BulkDtos.BulkItemDto verify(@PathVariable UUID id, @PathVariable int ordinal) {
        return bulk.verifyNow(id, ordinal);
    }
}
