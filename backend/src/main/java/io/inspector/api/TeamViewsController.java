package io.inspector.api;

import io.inspector.views.SharedView;
import io.inspector.views.SharedViewService;
import io.inspector.views.SharedViewService.VisibleSharedView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Team-published shared views (SPEC §8, SHARED-VIEWS.md, R-SEM-24 / R-SAFE-16) — the governed
 * successor-sibling of the per-user {@link ViewsController}. Ownership + the scoped RBAC checks are
 * server-side in {@link SharedViewService} (never from the client): publish/edit/unpublish resolve the
 * caller from {@code authentication} and gate on {@code covers()} / {@code canModerate()} there. The
 * {@code @PreAuthorize} floors are coarse defense-in-depth — READ is any authenticated user (visibility
 * is an {@code overlaps()} DECLUTTER filter, not a security boundary); PUBLISH needs at least an
 * OPERATOR somewhere (the precise per-scope gate, and the ADMIN escalation for a wildcard scope, live
 * in the service); MODERATE stays VIEWER-floor because an author may hold only VIEWER now yet still
 * edit their own canon — the service enforces author-or-scope-ADMIN.
 */
@RestController
@RequestMapping("/api/team-views")
public class TeamViewsController {

    private final SharedViewService service;

    public TeamViewsController(SharedViewService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public List<TeamViewDto> list(Authentication authentication) {
        return service.listVisibleForDisplay(authentication).stream()
                .map(TeamViewDto::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'OPERATOR')")
    public TeamViewDto publish(@Valid @RequestBody PublishRequest body, Authentication authentication) {
        SharedView v = service.publish(
                authentication,
                body.name(),
                body.search(),
                body.description(),
                body.runbookUrl(),
                body.scopeEngineId(),
                body.scopeTenantId());
        return TeamViewDto.from(new VisibleSharedView(v, null));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public TeamViewDto edit(
            @PathVariable Long id, @Valid @RequestBody EditRequest body, Authentication authentication) {
        SharedView v =
                service.edit(authentication, id, body.search(), body.description(), body.runbookUrl(), body.reason());
        return TeamViewDto.from(new VisibleSharedView(v, null));
    }

    @PostMapping("/{id}/unpublish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public void unpublish(
            @PathVariable Long id, @Valid @RequestBody ModerationRequest body, Authentication authentication) {
        service.unpublish(authentication, id, body.reason());
    }

    /**
     * A team view for the picker. {@code danglingReason} is non-null when the scoped engine is no
     * longer live (SHARED-VIEWS.md §4.5) — the frontend greys the chip with it, never a dead entry
     * point. {@code author}/{@code scope*} are attribution + the derived governance scope (both
     * server-derived; the client never asserts them).
     */
    public record TeamViewDto(
            Long id,
            String name,
            String search,
            String scopeEngineId,
            String scopeTenantId,
            String author,
            String description,
            String runbookUrl,
            String danglingReason,
            Instant createdAt,
            Instant updatedAt) {
        static TeamViewDto from(VisibleSharedView v) {
            SharedView s = v.view();
            return new TeamViewDto(
                    s.getId(),
                    s.getName(),
                    s.getSearch(),
                    s.getScopeEngineId(),
                    s.getScopeTenantId(),
                    s.getAuthor(),
                    s.getDescription(),
                    s.getRunbookUrl(),
                    v.danglingReason(),
                    s.getCreatedAt(),
                    s.getUpdatedAt());
        }
    }

    /**
     * Publish request. {@code scopeEngineId}/{@code scopeTenantId} are OPTIONAL — omitted, the server
     * DERIVES the scope from the search's engines (§4.2). A supplied scope is honored only if it still
     * contains the search's content (else 400), so it can only ever WIDEN (e.g. to global), never
     * narrow past the content. The bulk of validation (caps, blank, http(s) runbook) is in the service.
     */
    public record PublishRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 4000) String search,
            @Size(max = 500) String description,
            @Size(max = 2000) String runbookUrl,
            String scopeEngineId,
            String scopeTenantId) {}

    /** Edit the canon body (name + scope are the immutable identity). {@code reason} required to moderate another's. */
    public record EditRequest(
            @NotBlank @Size(max = 4000) String search,
            @Size(max = 500) String description,
            @Size(max = 2000) String runbookUrl,
            @Size(max = 500) String reason) {}

    /**
     * Unpublish; {@code reason} ≥10 chars (floor enforced in the service) is REQUIRED for every
     * caller, author included — unpublish is a moderation verb (usability W2 #3, R-SAFE-16) and the
     * reason is rendered in the operations log. The former reason-free {@code DELETE /{id}} alias is
     * gone: it existed solely for the author's reason-free path, which no longer exists.
     */
    public record ModerationRequest(@Size(max = 500) String reason) {}
}
