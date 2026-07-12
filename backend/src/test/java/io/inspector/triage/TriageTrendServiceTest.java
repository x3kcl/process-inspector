package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.dto.TriageTrendResponse;
import io.inspector.dto.TriageTrendResponse.Series;
import io.inspector.security.ReadScopeGate;
import io.inspector.snapshot.SnapshotCountRepository;
import io.inspector.snapshot.SnapshotLane;
import io.inspector.support.SnapshotCounts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/** Rung 1: the window→since arithmetic and the group-into-series reduction, repo mocked. */
class TriageTrendServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    private final SnapshotCountRepository repository = mock(SnapshotCountRepository.class);
    private final ReadScopeGate gate = mock(ReadScopeGate.class);
    private final Authentication auth = mock(Authentication.class);
    private final TriageTrendService service =
            new TriageTrendService(repository, Clock.fixed(NOW, ZoneOffset.UTC), gate);

    @Test
    void groupsRowsIntoPerEngineLaneSeriesAscendingByTime() {
        when(gate.readableEngineIds(auth)).thenReturn(null); // enforcement off — unrestricted
        when(repository.findBySampledAtGreaterThanEqualOrderByEngineIdAscLaneAscSampledAtAsc(
                        eq(NOW.minus(Duration.ofHours(24)))))
                .thenReturn(List.of(
                        SnapshotCounts.row("engine-a", SnapshotLane.ACTIVE, 5, "2026-07-08T11:00:00Z"),
                        SnapshotCounts.row("engine-a", SnapshotLane.ACTIVE, 7, "2026-07-08T11:30:00Z"),
                        SnapshotCounts.row("engine-a", SnapshotLane.FAILED, 2, "2026-07-08T11:30:00Z"),
                        SnapshotCounts.row("engine-b", SnapshotLane.ACTIVE, 1, "2026-07-08T11:30:00Z")));

        TriageTrendResponse out = service.trends(Duration.ofHours(24), auth);

        assertThat(out.asOf()).isEqualTo("2026-07-08T12:00:00Z");
        assertThat(out.window()).isEqualTo("PT24H");
        assertThat(out.series())
                .extracting(Series::engineId, Series::lane)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("engine-a", "ACTIVE"),
                        org.assertj.core.groups.Tuple.tuple("engine-a", "FAILED"),
                        org.assertj.core.groups.Tuple.tuple("engine-b", "ACTIVE"));
        // engine-a ACTIVE keeps both points in ascending time order with their counts.
        Series activeA = out.series().get(0);
        assertThat(activeA.points())
                .extracting(TriageTrendResponse.Point::count)
                .containsExactly(5L, 7L);
        assertThat(activeA.points())
                .extracting(TriageTrendResponse.Point::sampledAt)
                .isSorted();
    }

    @Test
    void emptyWindowYieldsNoSeries() {
        when(gate.readableEngineIds(auth)).thenReturn(null);
        when(repository.findBySampledAtGreaterThanEqualOrderByEngineIdAscLaneAscSampledAtAsc(
                        eq(NOW.minus(Duration.ofHours(6)))))
                .thenReturn(List.of());

        assertThat(service.trends(Duration.ofHours(6), auth).series()).isEmpty();
    }

    @Test
    void scopedCallerOnlySeesReadableEngineSeries() {
        // S2 (R-SAFE-17, issue #126): trends has no shared cache, so scoping is a plain row
        // filter — engine-b's series must never reach a caller who can't read engine-b.
        when(gate.readableEngineIds(auth)).thenReturn(Set.of("engine-a"));
        when(repository.findBySampledAtGreaterThanEqualOrderByEngineIdAscLaneAscSampledAtAsc(
                        eq(NOW.minus(Duration.ofHours(24)))))
                .thenReturn(List.of(
                        SnapshotCounts.row("engine-a", SnapshotLane.ACTIVE, 5, "2026-07-08T11:00:00Z"),
                        SnapshotCounts.row("engine-b", SnapshotLane.ACTIVE, 1, "2026-07-08T11:30:00Z")));

        TriageTrendResponse out = service.trends(Duration.ofHours(24), auth);

        assertThat(out.series()).extracting(Series::engineId).containsExactly("engine-a");
    }
}
