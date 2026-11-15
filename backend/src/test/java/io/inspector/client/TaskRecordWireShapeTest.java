package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed task records (#474) against the RECORDED flowable-rest 6.8 / 7.1 task documents
 * ({@code src/test/resources/wire/}). The mapper is a bare, STRICT {@link ObjectMapper} — it fails
 * on an unknown property unless the record itself tolerates it — so the tolerance is proven on
 * the record, not borrowed from Spring's lenient default. Every recorded task carries fields the
 * inspector never reads ({@code url}, {@code executionUrl}, {@code variables}, …) and the page
 * carries {@code order}/{@code sort}.
 */
class TaskRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private static <T> T read(String fixture, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in = TaskRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, type));
                    }
                })
                .as("deserialising wire/%s into %s", fixture, type.getSimpleName())
                .doesNotThrowAnyException();
        return out.get();
    }

    @Test
    void runtimeTaskOf68CarriesTheLiveOnlyFieldsAndNoHistoricOnes() {
        TaskRecord task = read("6.8/task.json", TaskRecord.class);

        assertThat(task.id()).isEqualTo("e5dadd5c-de72-11f1-a5cc-c2b29413085e");
        assertThat(task.name()).isEqualTo("Review order");
        assertThat(task.taskDefinitionKey()).isEqualTo("reviewOrder");
        assertThat(task.assignee()).isEqualTo("rest-admin");
        assertThat(task.owner()).isNull(); // explicit JSON null
        assertThat(task.description()).isNull();
        assertThat(task.category()).isNull();
        assertThat(task.priority()).isEqualTo(50);
        assertThat(task.processInstanceId()).isEqualTo("e5da6826-de72-11f1-a5cc-c2b29413085e");
        assertThat(task.processDefinitionId()).isEqualTo("demoUserTask:1:e599dd6f-de72-11f1-a5cc-c2b29413085e");
        assertThat(task.executionId()).isEqualTo("e5da8f38-de72-11f1-a5cc-c2b29413085e");
        assertThat(task.createTime()).isEqualTo("2026-11-12T08:27:28.914Z"); // verbatim string
        assertThat(task.dueDate()).isNull();
        assertThat(task.delegationState()).isNull();
        assertThat(task.suspended()).isFalse(); // a real false, not absent
        // The runtime row serialises none of the historic-only fields: absent is null.
        assertThat(task.startTime()).isNull();
        assertThat(task.endTime()).isNull();
        assertThat(task.durationInMillis()).isNull();
    }

    @Test
    void historicTaskOf68NamesItsStartStartTimeAndHasNoLiveState() {
        TaskRecord task = read("6.8/historic-task-instance.json", TaskRecord.class);

        assertThat(task.id()).isEqualTo("e5dadd5c-de72-11f1-a5cc-c2b29413085e");
        assertThat(task.processInstanceId()).isEqualTo("e5da6826-de72-11f1-a5cc-c2b29413085e");
        assertThat(task.startTime()).isEqualTo("2026-11-12T08:27:28.914Z"); // historic name of createTime
        assertThat(task.createTime()).isNull();
        assertThat(task.endTime()).isNull(); // still open: a historic row with a null endTime
        assertThat(task.durationInMillis()).isNull();
        // suspension and delegation exist on the runtime row only — absent is null, never false.
        assertThat(task.suspended()).isNull();
        assertThat(task.delegationState()).isNull();
    }

    @Test
    void runtimePageOf71KeepsTotalAndTypesEveryRow() {
        TaskPage page = read("7.1/tasks.json", TaskPage.class);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.start()).isZero();
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.dataOrEmpty()).hasSize(1);
        TaskRecord task = page.dataOrEmpty().get(0);
        assertThat(task.id()).isEqualTo("e791b56b-de72-11f1-9e47-dad7623d9163");
        assertThat(task.createTime()).isEqualTo("2026-11-12T08:27:31.791Z");
        assertThat(task.suspended()).isFalse();
        assertThat(task.priority()).isEqualTo(50);
    }

    @Test
    void historicPageOf71KeepsTotalAndTheHistoricStartTime() {
        TaskPage page = read("7.1/historic-task-instances.json", TaskPage.class);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.dataOrEmpty()).hasSize(1);
        TaskRecord task = page.dataOrEmpty().get(0);
        assertThat(task.id()).isEqualTo("e791b56b-de72-11f1-9e47-dad7623d9163");
        assertThat(task.taskDefinitionKey()).isEqualTo("reviewOrder");
        assertThat(task.startTime()).isEqualTo("2026-11-12T08:27:31.791Z");
        assertThat(task.endTime()).isNull();
        assertThat(task.assignee()).isEqualTo("rest-admin");
    }

    @Test
    void aPageWithoutDataIsAnEmptyListNotNull() {
        assertThat(new TaskPage(null, 0, 0, 0).dataOrEmpty()).isEmpty();
        assertThat(TaskPage.empty().dataOrEmpty()).isEmpty();
    }
}
