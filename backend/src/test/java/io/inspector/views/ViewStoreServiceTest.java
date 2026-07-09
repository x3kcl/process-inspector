package io.inspector.views;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Rung 1: ownership-scoping, upsert-by-name, and recents dedupe + cap — repos mocked. */
class ViewStoreServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T09:00:00Z");

    private final SavedViewRepository savedViews = Mockito.mock(SavedViewRepository.class);
    private final RecentSearchRepository recents = Mockito.mock(RecentSearchRepository.class);
    private final ViewStoreService service =
            new ViewStoreService(savedViews, recents, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void savingAnExistingNameReplacesInPlaceRatherThanInserting() {
        SavedView existing = new SavedView("viewer", "My view", "status=ACTIVE", Instant.EPOCH);
        when(savedViews.findByOwnerAndName("viewer", "My view")).thenReturn(Optional.of(existing));

        SavedView result = service.saveView("viewer", "My view", "status=FAILED");

        assertThat(result.getSearch()).isEqualTo("status=FAILED");
        assertThat(result.getCreatedAt()).isEqualTo(NOW);
        verify(savedViews, never()).save(any()); // replaced in place, no new row
    }

    @Test
    void savingANewNameInsertsUnderTheCaller() {
        when(savedViews.findByOwnerAndName("viewer", "New")).thenReturn(Optional.empty());
        when(savedViews.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveView("viewer", "  New  ", "status=SUSPENDED");

        ArgumentCaptor<SavedView> saved = ArgumentCaptor.forClass(SavedView.class);
        verify(savedViews).save(saved.capture());
        assertThat(saved.getValue().getOwner()).isEqualTo("viewer");
        assertThat(saved.getValue().getName()).isEqualTo("New"); // trimmed
    }

    @Test
    void deleteIsScopedToTheOwner() {
        when(savedViews.deleteByIdAndOwner(7L, "viewer")).thenReturn(1L);
        when(savedViews.deleteByIdAndOwner(8L, "viewer")).thenReturn(0L);

        assertThat(service.deleteView("viewer", 7L)).isTrue();
        assertThat(service.deleteView("viewer", 8L)).isFalse(); // not owned / absent
    }

    @Test
    void recordingAnExistingSearchBumpsItInsteadOfDuplicating() {
        RecentSearch existing = new RecentSearch("viewer", "status=FAILED", "old", Instant.EPOCH);
        when(recents.findByOwnerAndSearch("viewer", "status=FAILED")).thenReturn(Optional.of(existing));
        when(recents.findByOwnerOrderByAtDesc("viewer")).thenReturn(List.of(existing));

        service.recordRecent("viewer", "status=FAILED", "FAILED");

        assertThat(existing.getLabel()).isEqualTo("FAILED");
        assertThat(existing.getAt()).isEqualTo(NOW);
        verify(recents, never()).save(any());
    }

    @Test
    void recordingTrimsTheOwnersListToTheCap() {
        when(recents.findByOwnerAndSearch(any(), any())).thenReturn(Optional.empty());
        // 12 rows already present (newest-first) — the 2 oldest must be dropped to reach cap 10.
        List<RecentSearch> twelve = new ArrayList<>(IntStream.range(0, 12)
                .mapToObj(i -> new RecentSearch("viewer", "s" + i, "l" + i, NOW.minusSeconds(i)))
                .toList());
        when(recents.findByOwnerOrderByAtDesc("viewer")).thenReturn(twelve);

        List<RecentSearch> result = service.recordRecent("viewer", "s0", "l0");

        assertThat(result).hasSize(ViewStoreService.RECENT_CAP);
        ArgumentCaptor<List<RecentSearch>> dropped = ArgumentCaptor.forClass(List.class);
        verify(recents).deleteAll(dropped.capture());
        assertThat(dropped.getValue()).hasSize(2);
    }
}
