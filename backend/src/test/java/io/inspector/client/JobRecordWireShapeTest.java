package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed job records (#474) against the RECORDED flowable-rest 6.8 / 7.1 job documents
 * ({@code src/test/resources/wire/}). The mapper is a bare, STRICT {@link ObjectMapper} — it fails
 * on an unknown property unless the record itself tolerates it — so the tolerance is proven on
 * the record, not borrowed from Spring's lenient default. Every recorded job carries fields the
 * inspector never reads ({@code url}, {@code executionUrl}, {@code correlationId}, …) and the page
 * carries {@code order}/{@code sort}.
 */
class JobRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private static <T> T read(String fixture, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in = JobRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, type));
                    }
                })
                .as("deserialising wire/%s into %s", fixture, type.getSimpleName())
                .doesNotThrowAnyException();
        return out.get();
    }

    @Test
    void deadLetterPageOf68KeepsTheOffsetFormCreateTimeVerbatim() {
        JobPage page = read("6.8/deadletter-jobs-first-2.json", JobPage.class);

        assertThat(page.total()).isEqualTo(3); // total > data: the truncation signal survives typing
        assertThat(page.start()).isZero();
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.dataOrEmpty()).hasSize(2);
        JobRecord job = page.dataOrEmpty().get(0);
        assertThat(job.id()).isEqualTo("e5ae9dfa-de72-11f1-a5cc-c2b29413085e");
        assertThat(job.processInstanceId()).isEqualTo("e5acf040-de72-11f1-a5cc-c2b29413085e");
        assertThat(job.processDefinitionId()).isEqualTo("demoFailingPayment:1:e553376f-de72-11f1-a5cc-c2b29413085e");
        assertThat(job.executionId()).isEqualTo("e5ad8c86-de72-11f1-a5cc-c2b29413085e");
        assertThat(job.elementId()).isEqualTo("chargePayment");
        assertThat(job.elementName()).isEqualTo("Charge payment");
        assertThat(job.exceptionMessage()).isEqualTo("Error while evaluating expression: ${amount % divisor}");
        assertThat(job.retries()).isZero();
        // 6.8 serializes createTime in OFFSET form and dueDate in Z form — both stay verbatim strings.
        assertThat(job.createTime()).isEqualTo("2026-11-12T08:27:28.697+00:00");
        assertThat(job.dueDate()).isEqualTo("2026-11-12T08:27:29.690Z");
        assertThat(job.tenantId()).isEmpty();
        // 6.8 serializes neither handlerType nor scopeType on a job row: absent is null, not "".
        assertThat(job.handlerType()).isNull();
        assertThat(job.scopeType()).isNull();
    }

    @Test
    void singleDeadLetterJobOf71CarriesTheVersionSpecificFields() {
        JobRecord job = read("7.1/deadletter-job.json", JobRecord.class);

        assertThat(job.id()).isEqualTo("e76a7e19-de72-11f1-9e47-dad7623d9163");
        assertThat(job.processInstanceId()).isEqualTo("e7691e7f-de72-11f1-9e47-dad7623d9163");
        assertThat(job.createTime()).isEqualTo("2026-11-12T08:27:31.600Z"); // 7.1: Z form
        assertThat(job.handlerType()).isEqualTo("async-continuation"); // 7.x only
        assertThat(job.lockOwner()).isNull(); // explicit JSON null
        assertThat(job.lockExpirationTime()).isNull();
        assertThat(job.retries()).isZero();
        // 7.x appends the runtime ids to the message — the record must not trim it.
        assertThat(job.exceptionMessage())
                .startsWith("Error while evaluating expression: ${amount % divisor} with Execution[ id '")
                .endsWith("- parent 'e7691e7f-de72-11f1-9e47-dad7623d9163'");
    }

    @Test
    void emptyLaneOf68IsAnEmptyPageNotNull() {
        JobPage page = read("6.8/jobs.json", JobPage.class);

        assertThat(page.total()).isZero();
        assertThat(page.dataOrEmpty()).isEmpty();
    }

    @Test
    void timerLaneOf71DeserialisesEveryRecordedRow() {
        JobPage page = read("7.1/timer-jobs.json", JobPage.class);

        assertThat(page.dataOrEmpty()).isNotEmpty().hasSize((int) page.total());
        assertThat(page.dataOrEmpty().get(0).id()).isEqualTo("e7799963-de72-11f1-9e47-dad7623d9163");
        assertThat(page.dataOrEmpty().get(0).dueDate()).isEqualTo("2026-11-12T09:27:31.654Z");
    }
}
