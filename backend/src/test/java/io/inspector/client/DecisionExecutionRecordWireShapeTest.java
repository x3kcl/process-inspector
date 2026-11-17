package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the typed DMN decision-execution row and the rows-free count page (#474) against the
 * RECORDED flowable-rest 6.8 / 7.1 documents, read with a bare, STRICT {@link ObjectMapper}. The
 * decision fixture is one execution of a throwaway decision table called from a throwaway process
 * (a {@code flowable:type="dmn"} service task with activity id {@code decide}).
 */
class DecisionExecutionRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private static <T> T read(String fixture, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in =
                            DecisionExecutionRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, type));
                    }
                })
                .as("deserialising wire/%s into %s", fixture, type.getSimpleName())
                .doesNotThrowAnyException();
        return out.get();
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void decisionExecutionKeepsWhatTheDecisionsTabReads(String major) {
        DecisionExecutionPage page =
                read(major + "/dmn-historic-decision-executions.json", DecisionExecutionPage.class);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.dataOrEmpty()).hasSize(1);
        DecisionExecutionRecord row = page.dataOrEmpty().get(0);
        assertThat(row.id()).isNotBlank();
        assertThat(row.decisionKey()).isEqualTo("rec474Decision");
        // neither major serialises the older name — the reader's fallback stays a fallback
        assertThat(row.decisionDefinitionKey()).isNull();
        assertThat(row.activityId()).isEqualTo("decide");
        assertThat(row.failed()).isFalse(); // a real false, not absent
        // times verbatim, in the DMN engine's +00:00 form
        assertThat(row.startTime()).startsWith("2026-11-17T05:37:1").endsWith("+00:00");
        assertThat(row.endTime()).startsWith("2026-11-17T05:37:1").endsWith("+00:00");
    }

    /** A count-only read ({@code size=1}, read {@code total}) needs no rows: any paged document gives its total. */
    @ParameterizedTest
    @CsvSource({
        "6.8/deadletter-jobs.json",
        "7.1/deadletter-jobs.json",
        "6.8/historic-task-instances.json",
        "7.1/historic-task-instances.json",
        "6.8/cmmn-historic-case-instances-finished.json",
        "7.1/cmmn-historic-case-instances-finished.json"
    })
    void pageTotalReadsTheTotalOfAnyPagedDocument(String fixture) throws IOException {
        long recorded;
        try (InputStream in = DecisionExecutionRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
            recorded = STRICT.readTree(in).get("total").asLong();
        }

        assertThat(read(fixture, PageTotal.class).total()).isEqualTo(recorded);
    }
}
