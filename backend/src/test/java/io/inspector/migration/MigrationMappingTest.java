package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung-1 pure test: the migration-document {@code activityMappings} element serializes to the
 * exact three wire forms the Flowable engine converter reads (P0 spike: field is
 * {@code activityMappings}, discriminated by field presence), and malformed shapes are refused
 * before they can reach the engine.
 */
class MigrationMappingTest {

    @Test
    void oneToOneSerializesFromAndToActivityId() {
        MigrationMapping m = MigrationMapping.oneToOne("reviewTask", "approveTask");
        assertThat(m.form()).isEqualTo(MigrationMapping.Form.ONE_TO_ONE);
        assertThat(m.toWire())
                .containsExactly(entry("fromActivityId", "reviewTask"), entry("toActivityId", "approveTask"));
        assertThat(m.coveredFromIds()).containsExactly("reviewTask");
    }

    @Test
    void manyToOneSerializesFromActivityIdsArray() {
        MigrationMapping m = new MigrationMapping(null, List.of("a", "b"), "merged", null);
        assertThat(m.form()).isEqualTo(MigrationMapping.Form.MANY_TO_ONE);
        assertThat(m.toWire())
                .containsExactly(entry("fromActivityIds", List.of("a", "b")), entry("toActivityId", "merged"));
        assertThat(m.coveredFromIds()).containsExactly("a", "b");
    }

    @Test
    void oneToManySerializesToActivityIdsArray() {
        MigrationMapping m = new MigrationMapping("split", null, null, List.of("x", "y"));
        assertThat(m.form()).isEqualTo(MigrationMapping.Form.ONE_TO_MANY);
        assertThat(m.toWire())
                .containsExactly(entry("fromActivityId", "split"), entry("toActivityIds", List.of("x", "y")));
        assertThat(m.coveredFromIds()).containsExactly("split");
    }

    @Test
    void neitherFromFormIsRefused() {
        assertThatThrownBy(() -> new MigrationMapping(null, null, "to", null).form())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromActivityId");
    }

    @Test
    void bothFromFormsIsRefused() {
        assertThatThrownBy(() -> new MigrationMapping("a", List.of("b"), "to", null).form())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both");
    }

    @Test
    void neitherToFormIsRefused() {
        assertThatThrownBy(() -> new MigrationMapping("a", null, null, null).form())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toActivityId");
    }

    @Test
    void manyToManyIsRefused() {
        assertThatThrownBy(() -> new MigrationMapping(null, List.of("a", "b"), null, List.of("x", "y")).form())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("many-to-many");
    }

    @Test
    void blankIdsCountAsAbsent() {
        assertThatThrownBy(() -> new MigrationMapping("  ", null, "to", null).form())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
