package io.inspector.api;

import io.inspector.bulk.BulkDtos;
import io.inspector.bulk.BulkErrorClassService;
import io.inspector.bulk.BulkFilterService;
import io.inspector.bulk.BulkJobService;
import io.inspector.stream.SseHub;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final BulkFilterService filter;
    private final SseHub stream;

    public BulkController(
            BulkJobService bulk, BulkErrorClassService errorClass, BulkFilterService filter, SseHub stream) {
        this.bulk = bulk;
        this.errorClass = errorClass;
        this.filter = filter;
        this.stream = stream;
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

    /**
     * v1.x #2 — select-all-matching-filter bulk. Server-side re-resolution is BINDING: the
     * body carries the {@code SearchRequest} criteria, never an ID array — the BFF re-runs
     * the M2a plan exhaustively at execution time, records the resolved list in the audit
     * envelope BEFORE dispatch, then hands off to the staggered fan-out.
     */
    @PostMapping("/filter")
    @PreAuthorize("@rbac.atLeast(authentication, 'RESPONDER')")
    public BulkDtos.BulkJobDto submitFilter(
            @RequestBody BulkDtos.BulkFilterRequest request, Authentication authentication) {
        return filter.submit(request, authentication);
    }

    /**
     * v1.x #2 — the live progress stream (live-ui-sse doctrine): id-only {@code bulk-job}
     * events plus a 15s {@code ping}. The browser's EventSource authenticates via the
     * session cookie (it cannot send an Authorization header); secured like any API route.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public SseEmitter events(Authentication authentication) {
        return stream.subscribe(authentication.getName());
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

    /**
     * Deliberately OUTSIDE the dangerous-set re-auth gate (IDP-SECURITY.md §5): cancel is the safety
     * brake — it only STOPS dispatching (do-no-harm direction; sent items keep their outcome), and
     * demanding a re-auth bounce to stop an in-flight fan-out mid-incident would be harmful.
     * "Continue as new job" is a fresh submit and IS gated at the submit convergence.
     */
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
