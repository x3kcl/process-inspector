package io.inspector.views;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user Saved Views + Recent Searches store (SPEC §8, v2/M4). EVERY method is keyed on
 * {@code owner} (the authenticated user, from {@code Authentication#getName()}) — a caller can
 * only ever read or mutate their OWN rows. No corrective-action rails: these are the user's own
 * preferences, not engine mutations.
 */
@Service
public class ViewStoreService {

    /** Matches the client's recents cap (RECENT_CAP) — newest-first, older ones fall off. */
    static final int RECENT_CAP = 10;

    private final SavedViewRepository savedViews;
    private final RecentSearchRepository recents;
    private final Clock clock;

    public ViewStoreService(SavedViewRepository savedViews, RecentSearchRepository recents, Clock clock) {
        this.savedViews = savedViews;
        this.recents = recents;
        this.clock = clock;
    }

    /* ---------------- saved views ---------------- */

    public List<SavedView> listViews(String owner) {
        return savedViews.findByOwnerOrderByCreatedAtDesc(owner);
    }

    /** Upsert by (owner, name): re-saving a name replaces its search in place (client semantics). */
    @Transactional
    public SavedView saveView(String owner, String name, String search) {
        String trimmedName = name.trim();
        return savedViews
                .findByOwnerAndName(owner, trimmedName)
                .map(existing -> {
                    existing.replace(search, clock.instant());
                    return existing;
                })
                .orElseGet(() -> savedViews.save(new SavedView(owner, trimmedName, search, clock.instant())));
    }

    /** Ownership-scoped delete — returns true iff a row the caller owned was removed. */
    @Transactional
    public boolean deleteView(String owner, Long id) {
        return savedViews.deleteByIdAndOwner(id, owner) > 0;
    }

    /* ---------------- recent searches ---------------- */

    public List<RecentSearch> listRecents(String owner) {
        return recents.findByOwnerOrderByAtDesc(owner);
    }

    /**
     * Record a search: dedupe by (owner, search) — an existing entry is bumped to now rather than
     * duplicated — then trim the owner's list to the newest {@link #RECENT_CAP} (DROP the tail).
     */
    @Transactional
    public List<RecentSearch> recordRecent(String owner, String search, String label) {
        recents.findByOwnerAndSearch(owner, search)
                .ifPresentOrElse(
                        existing -> existing.touch(label, clock.instant()),
                        () -> recents.save(new RecentSearch(owner, search, label, clock.instant())));

        List<RecentSearch> all = recents.findByOwnerOrderByAtDesc(owner);
        if (all.size() > RECENT_CAP) {
            recents.deleteAll(all.subList(RECENT_CAP, all.size()));
            return all.subList(0, RECENT_CAP);
        }
        return all;
    }
}
