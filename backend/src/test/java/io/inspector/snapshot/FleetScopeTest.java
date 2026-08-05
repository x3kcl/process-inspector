package io.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the ONE canonicalizer every write path and every reader of the V22 {@code fleet} column
 * agrees on (ALARM-COST-MODEL §16.5, #372). Equality of these strings is the entire scope
 * discipline, so the guarantees are pinned rather than assumed.
 */
class FleetScopeTest {

    @Test
    void idsAreSortedLexicographicallyAndCommaJoined() {
        assertThat(FleetScope.canonical(ordered("engine-b", "engine-a", "engine-7")))
                .isEqualTo("engine-7,engine-a,engine-b");
    }

    @Test
    void aPureREORDERIsNotACompositionChange() {
        // perEngine is registry-ordered; moving an engine up the list must not read as a new fleet.
        assertThat(FleetScope.canonical(ordered("a", "b", "c")))
                .isEqualTo(FleetScope.canonical(ordered("c", "a", "b")));
    }

    @Test
    void aCompositionChangeIsVisibleAndASWAPAtConstantCountIsToo() {
        String before = FleetScope.canonical(ordered("engine-a", "engine-b"));
        assertThat(FleetScope.canonical(ordered("engine-a"))).isNotEqualTo(before); // a disable
        assertThat(FleetScope.canonical(ordered("engine-a", "engine-b", "engine-7")))
                .isNotEqualTo(before); // an enable
        // ...and the case a plain COUNT could never see (§16.4): disable b, enable c, count = 2.
        assertThat(FleetScope.canonical(ordered("engine-a", "engine-c"))).isNotEqualTo(before);
    }

    @Test
    void anEmptyOrNullScopeIsUNRECORDEDRatherThanAFabricatedEmptyFleet() {
        assertThat(FleetScope.canonical(null)).isEqualTo(FleetScope.UNRECORDED);
        assertThat(FleetScope.canonical(Set.of())).isEqualTo(FleetScope.UNRECORDED);
        assertThat(FleetScope.canonical(Arrays.asList(null, "  "))).isEqualTo(FleetScope.UNRECORDED);
        assertThat(FleetScope.UNRECORDED).isEmpty();
    }

    @Test
    void aDuplicateIdCannotInflateTheScopeString() {
        assertThat(FleetScope.canonical(List.of("engine-a", "engine-a", "engine-b")))
                .isEqualTo("engine-a,engine-b");
    }

    @Test
    void anIdContainingACommaDegradesToUNRECORDEDRatherThanRecordingAnAmbiguousScope() {
        // Foreclosed on every write path by ENGINE_ID_PATTERN / EngineRegistryStore.ID_PATTERN —
        // this is belt-and-braces. An unrecorded scope discards deltas (safe); an ALIASED one
        // would silently compare {"a,b"} equal to {"a","b"}, i.e. two different fleets (not safe).
        assertThat(FleetScope.canonical(ordered("engine-a,engine-b"))).isEqualTo(FleetScope.UNRECORDED);
        assertThat(FleetScope.canonical(ordered("engine-a", "b,c"))).isEqualTo(FleetScope.UNRECORDED);
    }

    private static Set<String> ordered(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }
}
