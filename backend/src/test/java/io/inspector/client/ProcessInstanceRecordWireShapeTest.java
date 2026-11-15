package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed process-instance records (#474) against the RECORDED flowable-rest 6.8 / 7.1
 * documents ({@code src/test/resources/wire/}). The mapper is a bare, STRICT {@link ObjectMapper}
 * — it fails on an unknown property unless the record itself tolerates it — so the tolerance is
 * proven on the record, not borrowed from Spring's lenient default. Every recorded row carries
 * fields the inspector never reads ({@code url}, {@code processDefinitionUrl}, {@code callbackId},
 * …) and the page carries {@code order}/{@code sort}.
 *
 * <p>No 6.3 document is recorded (the legacy engine runs in the merge-queue batch only), so the
 * pre-6.4 shape is pinned as an inline document reduced to the fields that engine serialises.
 */
class ProcessInstanceRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private static <T> T read(String fixture, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in =
                            ProcessInstanceRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, type));
                    }
                })
                .as("deserialising wire/%s into %s", fixture, type.getSimpleName())
                .doesNotThrowAnyException();
        return out.get();
    }

    private static ProcessInstanceRecord parse(String json) {
        AtomicReference<ProcessInstanceRecord> out = new AtomicReference<>();
        assertThatCode(() -> out.set(STRICT.readValue(json, ProcessInstanceRecord.class)))
                .as("deserialising %s", json)
                .doesNotThrowAnyException();
        return out.get();
    }

    @Test
    void runtimeInstanceOf68CarriesTheLiveStateAndNoHistoricFields() {
        ProcessInstanceRecord pi = read("6.8/runtime-process-instance.json", ProcessInstanceRecord.class);

        assertThat(pi.id()).isEqualTo("e5acf040-de72-11f1-a5cc-c2b29413085e");
        assertThat(pi.processDefinitionId()).isEqualTo("demoFailingPayment:1:e553376f-de72-11f1-a5cc-c2b29413085e");
        assertThat(pi.processDefinitionName()).isEqualTo("Demo Failing Payment");
        assertThat(pi.startTime()).isEqualTo("2026-11-12T08:27:28.615Z"); // verbatim string
        assertThat(pi.startUserId()).isEqualTo("rest-admin");
        assertThat(pi.tenantId()).isEmpty(); // "" on the wire, not null
        assertThat(pi.businessKey()).isNull(); // explicit JSON null
        assertThat(pi.name()).isNull();
        assertThat(pi.suspended()).isFalse(); // a real false, not absent
        assertThat(pi.variables()).isEmpty();
        // 6.8's runtime row does not serialise superProcessInstanceId at all (7.1 does): absent is null.
        assertThat(pi.superProcessInstanceId()).isNull();
        // The runtime row serialises none of the historic-only fields.
        assertThat(pi.endTime()).isNull();
        assertThat(pi.durationInMillis()).isNull();
        assertThat(pi.deleteReason()).isNull();
        assertThat(pi.startActivityId()).isNull();
        assertThat(pi.endActivityId()).isNull();
    }

    @Test
    void historicInstanceOf68IsOpenAndHasNoSuspensionState() {
        ProcessInstanceRecord pi = read("6.8/historic-process-instance.json", ProcessInstanceRecord.class);

        assertThat(pi.id()).isEqualTo("e5acf040-de72-11f1-a5cc-c2b29413085e");
        assertThat(pi.processDefinitionId()).isEqualTo("demoFailingPayment:1:e553376f-de72-11f1-a5cc-c2b29413085e");
        assertThat(pi.startTime()).isEqualTo("2026-11-12T08:27:28.615Z");
        assertThat(pi.startActivityId()).isEqualTo("start");
        assertThat(pi.endTime()).isNull(); // still running: the status join's "open" signal
        assertThat(pi.durationInMillis()).isNull();
        assertThat(pi.endActivityId()).isNull();
        assertThat(pi.superProcessInstanceId()).isNull();
        // Suspension exists on the runtime row only — absent is null, never false.
        assertThat(pi.suspended()).isNull();
    }

    @Test
    void runtimeAndHistoricInstanceOf71ReadTheSameWay() {
        ProcessInstanceRecord runtime = read("7.1/runtime-process-instance.json", ProcessInstanceRecord.class);
        ProcessInstanceRecord historic = read("7.1/historic-process-instance.json", ProcessInstanceRecord.class);

        assertThat(runtime.id()).isEqualTo("e7691e7f-de72-11f1-9e47-dad7623d9163");
        assertThat(historic.id()).isEqualTo(runtime.id());
        assertThat(runtime.processDefinitionId())
                .isEqualTo("demoFailingPayment:1:e71b257e-de72-11f1-9e47-dad7623d9163");
        assertThat(runtime.startTime()).isEqualTo("2026-11-12T08:27:31.526Z");
        assertThat(historic.startTime()).isEqualTo(runtime.startTime());
        assertThat(runtime.suspended()).isFalse();
        assertThat(historic.suspended()).isNull();
        assertThat(runtime.superProcessInstanceId()).isNull(); // 7.1 serialises it, as an explicit null
        assertThat(historic.endTime()).isNull();
    }

    @Test
    void finishedHistoricPageKeepsEndTimeDurationAndEndActivity() {
        ProcessInstancePage page68 =
                read("6.8/query-historic-process-instances-finished.json", ProcessInstancePage.class);
        ProcessInstancePage page71 =
                read("7.1/query-historic-process-instances-finished.json", ProcessInstancePage.class);

        assertThat(page68.total()).isEqualTo(1);
        assertThat(page68.dataOrEmpty()).hasSize(1);
        ProcessInstanceRecord done68 = page68.dataOrEmpty().get(0);
        assertThat(done68.id()).isEqualTo("e5cb73fe-de72-11f1-a5cc-c2b29413085e");
        assertThat(done68.endTime()).isEqualTo("2026-11-12T08:27:28.863Z");
        assertThat(done68.durationInMillis()).isEqualTo(48L);
        assertThat(done68.endActivityId()).isEqualTo("end");
        assertThat(done68.deleteReason()).isNull();
        assertThat(done68.processDefinitionName()).isEqualTo("Demo Order (happy path)");

        ProcessInstanceRecord done71 = page71.dataOrEmpty().get(0);
        assertThat(done71.id()).isEqualTo("e7877c2d-de72-11f1-9e47-dad7623d9163");
        assertThat(done71.endTime()).isEqualTo("2026-11-12T08:27:31.760Z");
        assertThat(done71.durationInMillis()).isEqualTo(35L);
    }

    @Test
    void queryPagesKeepTheTotalBeyondThePageAndTypeEveryRow() {
        ProcessInstancePage runtime = read("7.1/query-process-instances.json", ProcessInstancePage.class);
        ProcessInstancePage historic = read("6.8/query-historic-process-instances.json", ProcessInstancePage.class);

        // size=1 count probe: one row on the page, the total counts every match.
        assertThat(runtime.total()).isEqualTo(6);
        assertThat(runtime.size()).isEqualTo(1);
        assertThat(runtime.start()).isZero();
        assertThat(runtime.dataOrEmpty()).hasSize(1);
        assertThat(runtime.dataOrEmpty().get(0).id()).isEqualTo("e7691e7f-de72-11f1-9e47-dad7623d9163");
        assertThat(runtime.dataOrEmpty().get(0).suspended()).isFalse();

        assertThat(historic.total()).isEqualTo(7);
        assertThat(historic.dataOrEmpty()).hasSize(7);
        assertThat(historic.dataOrEmpty().get(0).id()).isEqualTo("e5da6826-de72-11f1-a5cc-c2b29413085e");
        assertThat(historic.dataOrEmpty().get(0).startTime()).isEqualTo("2026-11-12T08:27:28.913Z");
        assertThat(historic.dataOrEmpty())
                .allSatisfy(row -> assertThat(row.id()).isNotBlank());
    }

    @Test
    void anEmptySuspendedPageIsAnEmptyListWithAZeroTotal() {
        ProcessInstancePage page = read("6.8/query-process-instances-suspended.json", ProcessInstancePage.class);

        assertThat(page.total()).isZero();
        assertThat(page.dataOrEmpty()).isEmpty();
    }

    @Test
    void aPageWithoutDataIsAnEmptyListNotNull() {
        assertThat(new ProcessInstancePage(null, 0, 0, 0).dataOrEmpty()).isEmpty();
        assertThat(ProcessInstancePage.empty().dataOrEmpty()).isEmpty();
        assertThat(ProcessInstancePage.empty().total()).isZero();
    }

    @Test
    void aPre64RowWithoutTheLaterFieldsLeavesThemNull() {
        // 6.3 predates businessStatus / callbackId / referenceId / processDefinitionName on the row.
        ProcessInstanceRecord pi = parse("""
                {"id":"4711","url":"http://e/runtime/process-instances/4711","businessKey":"ORD-1",
                 "suspended":true,"ended":false,"processDefinitionId":"order:3:99",
                 "processDefinitionUrl":"http://e/repository/process-definitions/order:3:99",
                 "activityId":null,"variables":[],"tenantId":"","completed":false}
                """);

        assertThat(pi.id()).isEqualTo("4711");
        assertThat(pi.businessKey()).isEqualTo("ORD-1");
        assertThat(pi.suspended()).isTrue();
        assertThat(pi.processDefinitionId()).isEqualTo("order:3:99");
        assertThat(pi.processDefinitionName()).isNull();
        assertThat(pi.startTime()).isNull();
        assertThat(pi.startUserId()).isNull();
        assertThat(pi.superProcessInstanceId()).isNull();
    }

    @Test
    void timesStayVerbatimAndVariableValuesStayDynamic() {
        // The engine's offset form is kept as sent — never normalised to an Instant or to Z.
        ProcessInstanceRecord pi = parse("""
                {"id":"1","startTime":"2026-11-12T09:27:28.615+01:00","endTime":"2026-11-12T09:27:29.000+01:00",
                 "durationInMillis":385,"superProcessInstanceId":"0",
                 "variables":[{"name":"amount","type":"integer","value":42,"scope":"local"},
                              {"name":"order","type":"json","value":{"lines":[1,2]},"scope":"local"}],
                 "aFieldFromAFutureEngine":{"x":1}}
                """);

        assertThat(pi.startTime()).isEqualTo("2026-11-12T09:27:28.615+01:00");
        assertThat(pi.endTime()).isEqualTo("2026-11-12T09:27:29.000+01:00");
        assertThat(pi.durationInMillis()).isEqualTo(385L);
        assertThat(pi.superProcessInstanceId()).isEqualTo("0");
        assertThat(pi.variables()).hasSize(2);
        assertThat(pi.variables().get(0)).containsEntry("name", "amount").containsEntry("value", 42);
        assertThat(pi.variables().get(1).get("value")).isEqualTo(java.util.Map.of("lines", java.util.List.of(1, 2)));
    }
}
