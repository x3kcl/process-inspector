package io.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThroughputComparisonTest {

    @Test
    void noSamplesMeansNoLine() {
        assertThat(ThroughputComparison.of(List.of(), 10).current()).isNull();
    }

    @Test
    void aShortSeriesHasACurrentWindowAndNoReference() {
        ThroughputComparison.Windows windows = ThroughputComparison.of(List.of(2L, 2L), 10);

        assertThat(windows.current()).isEqualTo(4L);
        assertThat(windows.referenceMedian()).isNull();
    }

    @Test
    void theReferenceIsTheMedianOfPreviousWindows() {
        // newest first. width 2. current = 1+1. previous windows: 9+9 and 3+3. median 6.
        ThroughputComparison.Windows windows = ThroughputComparison.of(List.of(1L, 1L, 9L, 9L, 3L, 3L), 2);

        assertThat(windows.current()).isEqualTo(2L);
        assertThat(windows.referenceMedian()).isEqualTo(18L);
    }
}
