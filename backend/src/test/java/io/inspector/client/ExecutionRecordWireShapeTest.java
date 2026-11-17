package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed execution, historic-activity-instance and event-subscription records (#474)
 * against the RECORDED flowable-rest 6.8 / 7.1 documents ({@code src/test/resources/wire/}). The
 * mapper is a bare, STRICT {@link ObjectMapper} — it fails on an unknown property unless the record
 * itself tolerates it — so the tolerance is proven on the record, not borrowed from Spring's
 * lenient default. Every recorded row carries fields the inspector never reads ({@code url},
 * {@code parentUrl}, {@code processInstanceUrl}, {@code superExecutionUrl},
 * {@code processDefinitionUrl}, {@code executionUrl}, {@code scopeId}, {@code scopeType}, …) and
 * the page carries {@code order}/{@code sort}.
 */
class ExecutionRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();
    private static final List<String> MAJORS = List.of("6.8", "7.1");

    private static <T> T read(String fixture, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in = ExecutionRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, type));
                    }
                })
                .as("deserialising wire/%s into %s", fixture, type.getSimpleName())
                .doesNotThrowAnyException();
        return out.get();
    }

    private static <T> T parse(String json, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> out.set(STRICT.readValue(json, type)))
                .as("deserialising %s", json)
                .doesNotThrowAnyException();
        return out.get();
    }

    /* ---------------- executions ---------------- */

    @Test
    void executionOf68KeepsEveryFieldTheReadersUse() {
        ExecutionRecord execution = read("6.8/execution.json", ExecutionRecord.class);

        assertThat(execution.id()).isEqualTo("e5ad8c86-de72-11f1-a5cc-c2b29413085e");
        assertThat(execution.parentId()).isEqualTo("e5acf040-de72-11f1-a5cc-c2b29413085e");
        assertThat(execution.processInstanceId()).isEqualTo("e5acf040-de72-11f1-a5cc-c2b29413085e");
        assertThat(execution.activityId()).isEqualTo("chargePayment");
        assertThat(execution.superExecutionId()).isNull(); // explicit JSON null
        assertThat(execution.suspended()).isFalse(); // a real false, not absent
        assertThat(execution.tenantId()).isEmpty(); // "" on the wire, not null
    }

    @Test
    void executionOf71ReadsTheSameWay() {
        ExecutionRecord execution = read("7.1/execution.json", ExecutionRecord.class);

        assertThat(execution.id()).isEqualTo("e76993b5-de72-11f1-9e47-dad7623d9163");
        assertThat(execution.parentId()).isEqualTo("e7691e7f-de72-11f1-9e47-dad7623d9163");
        assertThat(execution.processInstanceId()).isEqualTo("e7691e7f-de72-11f1-9e47-dad7623d9163");
        assertThat(execution.activityId()).isEqualTo("chargePayment");
        assertThat(execution.suspended()).isFalse();
    }

    @Test
    void executionPageKeepsTheRootWithoutAPositionAndTheChildAtItsActivity() {
        for (String v : MAJORS) {
            ExecutionPage page = read(v + "/executions.json", ExecutionPage.class);

            assertThat(page.total()).as(v).isEqualTo(2);
            assertThat(page.size()).as(v).isEqualTo(2);
            assertThat(page.dataOrEmpty()).as(v).hasSize(2);
            ExecutionRecord root = page.dataOrEmpty().get(0);
            // The root execution IS the process instance: no parent, no position.
            assertThat(root.id()).as(v).isEqualTo(root.processInstanceId());
            assertThat(root.parentId()).as(v).isNull();
            assertThat(root.activityId()).as(v).isNull();
            assertThat(page.dataOrEmpty().get(1)).as(v).isEqualTo(read(v + "/execution.json", ExecutionRecord.class));
        }
    }

    @Test
    void forkedInstanceKeepsOneChildExecutionPerToken() {
        for (String v : MAJORS) {
            ExecutionPage page = read(v + "/executions-forked.json", ExecutionPage.class);

            assertThat(page.total()).as(v).isEqualTo(3);
            String root = page.dataOrEmpty().get(0).id();
            assertThat(page.dataOrEmpty())
                    .as(v)
                    .extracting(ExecutionRecord::activityId)
                    .containsExactly(null, "waitForMessage", "waitForSignal");
            assertThat(page.dataOrEmpty())
                    .as(v)
                    .extracting(ExecutionRecord::parentId)
                    .containsExactly(null, root, root);
        }
    }

    @Test
    void executionWithoutTheOptionalFieldsLeavesThemNull() {
        ExecutionRecord execution =
                parse("{\"id\":\"e-1\",\"activityId\":\" \",\"futureField\":{\"a\":1}}", ExecutionRecord.class);

        assertThat(execution.id()).isEqualTo("e-1");
        assertThat(execution.activityId()).isEqualTo(" "); // blank stays blank — the old str() read
        assertThat(execution.suspended()).isNull(); // absent is null, never false
        assertThat(execution.parentId()).isNull();
        assertThat(execution.processInstanceId()).isNull();
        assertThat(execution.tenantId()).isNull();
    }

    /* ---------------- historic activity instances ---------------- */

    @Test
    void finishedActivityOf68KeepsItsVerbatimTimesAndDuration() {
        ActivityInstancePage page = read("6.8/historic-activity-instances.json", ActivityInstancePage.class);

        assertThat(page.total()).isEqualTo(2);
        ActivityInstanceRecord start = page.dataOrEmpty().get(0);
        assertThat(start.id()).isEqualTo("e5ad8c87-de72-11f1-a5cc-c2b29413085e");
        assertThat(start.activityId()).isEqualTo("start");
        assertThat(start.activityName()).isNull(); // explicit JSON null
        assertThat(start.activityType()).isEqualTo("startEvent");
        assertThat(start.executionId()).isEqualTo("e5ad8c86-de72-11f1-a5cc-c2b29413085e");
        assertThat(start.processInstanceId()).isEqualTo("e5acf040-de72-11f1-a5cc-c2b29413085e");
        assertThat(start.processDefinitionId()).isEqualTo("demoFailingPayment:1:e553376f-de72-11f1-a5cc-c2b29413085e");
        assertThat(start.startTime()).isEqualTo("2026-11-12T08:27:28.619Z"); // verbatim string
        assertThat(start.endTime()).isEqualTo("2026-11-12T08:27:28.623Z");
        assertThat(start.durationInMillis()).isEqualTo(4L);
        assertThat(start.taskId()).isNull();
        assertThat(start.calledProcessInstanceId()).isNull();
        assertThat(start.assignee()).isNull();
        assertThat(start.tenantId()).isEmpty(); // "" on the wire, not null
    }

    @Test
    void sequenceFlowRowsSurviveWithAZeroDuration() {
        for (String v : MAJORS) {
            ActivityInstanceRecord flow = read(v + "/historic-activity-instances.json", ActivityInstancePage.class)
                    .dataOrEmpty()
                    .get(1);

            // The execution overlay colours the taken flows from these rows.
            assertThat(flow.activityType()).as(v).isEqualTo("sequenceFlow");
            assertThat(flow.activityId()).as(v).isEqualTo("flow1");
            assertThat(flow.durationInMillis()).as(v).isEqualTo(0L); // a real 0, not absent
            assertThat(flow.endTime()).as(v).isEqualTo(flow.startTime());
        }
    }

    @Test
    void activityOf71ReadsTheSameWay() {
        ActivityInstanceRecord start = read("7.1/historic-activity-instances.json", ActivityInstancePage.class)
                .dataOrEmpty()
                .get(0);

        assertThat(start.id()).isEqualTo("e76993b6-de72-11f1-9e47-dad7623d9163");
        assertThat(start.activityId()).isEqualTo("start");
        assertThat(start.startTime()).isEqualTo("2026-11-12T08:27:31.529Z");
        assertThat(start.endTime()).isEqualTo("2026-11-12T08:27:31.532Z");
        assertThat(start.durationInMillis()).isEqualTo(3L);
    }

    @Test
    void unfinishedActivityHasNoEndTimeAndNoDuration() {
        for (String v : MAJORS) {
            ActivityInstancePage page =
                    read(v + "/historic-activity-instances-unfinished.json", ActivityInstancePage.class);

            assertThat(page.total()).as(v).isEqualTo(2);
            ActivityInstanceRecord waiting = page.dataOrEmpty().get(0);
            assertThat(waiting.activityId()).as(v).isEqualTo("waitForMessage");
            assertThat(waiting.activityName()).as(v).isEqualTo("Wait for go");
            assertThat(waiting.activityType()).as(v).isEqualTo("intermediateCatchEvent");
            assertThat(waiting.startTime()).as(v).startsWith("2026-11-17T03:46:4");
            assertThat(waiting.endTime()).as(v).isNull(); // unfinished
            assertThat(waiting.durationInMillis()).as(v).isNull(); // null, never 0
            assertThat(page.dataOrEmpty().get(1).activityName()).as(v).isNull();
        }
    }

    @Test
    void activityWithoutTheOptionalFieldsLeavesThemNull() {
        ActivityInstanceRecord row = parse(
                "{\"activityId\":\"callSub\",\"calledProcessInstanceId\":\"child-1\",\"futureField\":[1]}",
                ActivityInstanceRecord.class);

        assertThat(row.activityId()).isEqualTo("callSub");
        assertThat(row.calledProcessInstanceId()).isEqualTo("child-1");
        assertThat(row.id()).isNull();
        assertThat(row.durationInMillis()).isNull(); // absent is null, never 0
        assertThat(row.endTime()).isNull();
        assertThat(row.activityName()).isNull();
    }

    /* ---------------- event subscriptions ---------------- */

    @Test
    void waitingInstanceKeepsItsMessageAndSignalSubscriptions() {
        EventSubscriptionPage page = read("6.8/event-subscriptions-waiting.json", EventSubscriptionPage.class);

        assertThat(page.total()).isEqualTo(2);
        EventSubscriptionRecord message = page.dataOrEmpty().get(0);
        assertThat(message.id()).isEqualTo("833fb653-e239-11f1-937e-6aebd179bc16");
        assertThat(message.eventType()).isEqualTo("message"); // lower case on the wire
        assertThat(message.eventName()).isEqualTo("wireProbeGo");
        assertThat(message.activityId()).isEqualTo("waitForMessage");
        assertThat(message.executionId()).isEqualTo("833f8f3b-e239-11f1-937e-6aebd179bc16");
        assertThat(message.processInstanceId()).isEqualTo("833f8f39-e239-11f1-937e-6aebd179bc16");
        assertThat(message.processDefinitionId()).isEqualTo("wireProbe474:1:83330c17-e239-11f1-937e-6aebd179bc16");
        assertThat(message.created()).isEqualTo("2026-11-17T03:46:46.820Z"); // verbatim string
        assertThat(message.configuration()).isNull();
        assertThat(message.tenantId()).isEmpty();
        EventSubscriptionRecord signal = page.dataOrEmpty().get(1);
        assertThat(signal.eventType()).isEqualTo("signal");
        assertThat(signal.eventName()).isEqualTo("wireProbeSignal");
        assertThat(signal.activityId()).isEqualTo("waitForSignal");
    }

    @Test
    void waitingInstanceOf71ReadsTheSameWay() {
        EventSubscriptionPage page = read("7.1/event-subscriptions-waiting.json", EventSubscriptionPage.class);

        assertThat(page.dataOrEmpty())
                .extracting(EventSubscriptionRecord::eventType)
                .containsExactly("message", "signal");
        assertThat(page.dataOrEmpty())
                .extracting(EventSubscriptionRecord::activityId)
                .containsExactly("waitForMessage", "waitForSignal");
        assertThat(page.dataOrEmpty().get(0).processInstanceId()).isEqualTo("837fa5de-e239-11f1-acb8-7eebefdaa7d9");
        assertThat(page.dataOrEmpty().get(0).created()).isEqualTo("2026-11-17T03:46:47.243Z");
    }

    @Test
    void definitionSubscriptionsTellTheStartSubscriptionByItsMissingInstance() {
        for (String v : MAJORS) {
            EventSubscriptionPage page =
                    read(v + "/event-subscriptions-by-definition.json", EventSubscriptionPage.class);

            assertThat(page.total()).as(v).isEqualTo(3);
            EventSubscriptionRecord start = page.dataOrEmpty().get(0);
            assertThat(start.eventName()).as(v).isEqualTo("wireProbeStart");
            assertThat(start.activityId()).as(v).isEqualTo("startByMessage");
            assertThat(start.processInstanceId()).as(v).isNull(); // a start subscription has no instance
            assertThat(start.executionId()).as(v).isNull();
            assertThat(start.configuration()).as(v).isEqualTo(start.processDefinitionId());
            assertThat(page.dataOrEmpty().subList(1, 3))
                    .as(v)
                    .allSatisfy(row -> assertThat(row.processInstanceId()).isNotNull());
        }
    }

    @Test
    void noSubscriptionsIsAnEmptyPage() {
        for (String v : MAJORS) {
            EventSubscriptionPage page = read(v + "/event-subscriptions.json", EventSubscriptionPage.class);

            assertThat(page.total()).as(v).isZero();
            assertThat(page.dataOrEmpty()).as(v).isEmpty();
        }
    }

    @Test
    void subscriptionWithoutTheOptionalFieldsLeavesThemNull() {
        EventSubscriptionRecord row =
                parse("{\"id\":\"s-1\",\"eventName\":\"\",\"futureField\":true}", EventSubscriptionRecord.class);

        assertThat(row.id()).isEqualTo("s-1");
        assertThat(row.eventName()).isEmpty(); // "" stays "", the old str() read
        assertThat(row.eventType()).isNull(); // the vitals strip defaults an absent type itself
        assertThat(row.created()).isNull();
        assertThat(row.processInstanceId()).isNull();
    }

    /* ---------------- pages ---------------- */

    @Test
    void emptyPagesAndNullDataAreAnEmptyList() {
        assertThat(ExecutionPage.empty().dataOrEmpty()).isEmpty();
        assertThat(ExecutionPage.empty().total()).isZero();
        assertThat(ActivityInstancePage.empty().dataOrEmpty()).isEmpty();
        assertThat(EventSubscriptionPage.empty().dataOrEmpty()).isEmpty();
        assertThat(parse("{\"total\":7,\"sort\":\"id\"}", ExecutionPage.class).dataOrEmpty())
                .isEmpty();
        assertThat(parse("{\"total\":7,\"sort\":\"id\"}", ExecutionPage.class).total())
                .isEqualTo(7);
        assertThat(parse("{\"total\":3}", ActivityInstancePage.class).dataOrEmpty())
                .isEmpty();
        assertThat(parse("{\"total\":3}", ActivityInstancePage.class).total()).isEqualTo(3);
        assertThat(parse("{\"total\":2}", EventSubscriptionPage.class).dataOrEmpty())
                .isEmpty();
    }
}
