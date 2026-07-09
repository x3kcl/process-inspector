package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung-1 pure test: the token-position digest that binds preview to execute (decision P0-4). It
 * is order-independent, counts multiplicity (a parallel gateway spawning a second token at the
 * same activity changes it), and is deterministic across calls.
 */
class ActivityStateDigestTest {

    @Test
    void isOrderIndependent() {
        assertThat(ActivityStateDigest.of(List.of("a", "b", "c")))
                .isEqualTo(ActivityStateDigest.of(List.of("c", "a", "b")));
    }

    @Test
    void countsMultiplicity() {
        // two tokens at 'a' is a different state than one token at 'a'
        assertThat(ActivityStateDigest.of(List.of("a", "a"))).isNotEqualTo(ActivityStateDigest.of(List.of("a")));
    }

    @Test
    void differentActivitiesDifferentDigest() {
        assertThat(ActivityStateDigest.of(List.of("reviewTask")))
                .isNotEqualTo(ActivityStateDigest.of(List.of("approveTask")));
    }

    @Test
    void isDeterministicAndHexShaped() {
        String a = ActivityStateDigest.of(List.of("reviewTask", "reviewTask", "sideTask"));
        String b = ActivityStateDigest.of(List.of("sideTask", "reviewTask", "reviewTask"));
        assertThat(a).isEqualTo(b).matches("[0-9a-f]{64}");
    }

    @Test
    void ignoresNulls() {
        List<String> withNull = java.util.Arrays.asList("a", null, "b");
        assertThat(ActivityStateDigest.of(withNull)).isEqualTo(ActivityStateDigest.of(List.of("a", "b")));
    }
}
