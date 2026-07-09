package io.inspector.api;

import io.inspector.views.RecentSearch;
import io.inspector.views.SavedView;
import io.inspector.views.ViewStoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user Saved Views + Recent Searches (SPEC §8, v2/M4) — the server-backed replacement for the
 * v1 localStorage stores. Every route is keyed to the CALLER: ownership comes from
 * {@code authentication.getName()} server-side, never from the client, so a user can only ever
 * see or mutate their own rows. VIEWER floor (any authenticated user manages their own views).
 * System views (R-SEM-05 relative windows) stay client-derived and are not stored here.
 */
@RestController
@RequestMapping("/api")
public class ViewsController {

    private final ViewStoreService store;

    public ViewsController(ViewStoreService store) {
        this.store = store;
    }

    @GetMapping("/views")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public List<SavedViewDto> listViews(Authentication authentication) {
        return store.listViews(authentication.getName()).stream()
                .map(SavedViewDto::from)
                .toList();
    }

    /** Upsert by name (re-saving a name replaces it) — the client's save semantics. */
    @PutMapping("/views")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public SavedViewDto saveView(@Valid @RequestBody SaveViewRequest body, Authentication authentication) {
        return SavedViewDto.from(store.saveView(authentication.getName(), body.name(), body.search()));
    }

    @DeleteMapping("/views/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public void deleteView(@PathVariable Long id, Authentication authentication) {
        if (!store.deleteView(authentication.getName(), id)) {
            // Absent OR owned by someone else — same 404 either way (never leak another user's ids).
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such saved view");
        }
    }

    @GetMapping("/recents")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public List<RecentSearchDto> listRecents(Authentication authentication) {
        return store.listRecents(authentication.getName()).stream()
                .map(RecentSearchDto::from)
                .toList();
    }

    /** Record a just-executed search; returns the caller's updated recents (newest-first, capped). */
    @PostMapping("/recents")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    public List<RecentSearchDto> recordRecent(
            @Valid @RequestBody RecordRecentRequest body, Authentication authentication) {
        return store.recordRecent(authentication.getName(), body.search(), body.label()).stream()
                .map(RecentSearchDto::from)
                .toList();
    }

    public record SavedViewDto(Long id, String name, String search, Instant createdAt) {
        static SavedViewDto from(SavedView v) {
            return new SavedViewDto(v.getId(), v.getName(), v.getSearch(), v.getCreatedAt());
        }
    }

    public record RecentSearchDto(String search, String label, Instant at) {
        static RecentSearchDto from(RecentSearch r) {
            return new RecentSearchDto(r.getSearch(), r.getLabel(), r.getAt());
        }
    }

    public record SaveViewRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 4000) String search) {}

    public record RecordRecentRequest(
            @NotBlank @Size(max = 4000) String search,
            @NotBlank @Size(max = 500) String label) {}
}
