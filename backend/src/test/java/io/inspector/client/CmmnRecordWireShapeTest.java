package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed CMMN records (#474) — case instances, plan items, CMMN jobs — against the RECORDED
 * flowable-rest 6.8 / 7.1 {@code /cmmn-api} documents ({@code src/test/resources/wire/}, one
 * dead-lettering case and one terminated case of {@code demo-failing-case.cmmn.xml}). The mapper is
 * a bare, STRICT {@link ObjectMapper}, so the tolerance of the fields the inspector never reads
 * ({@code url}, {@code caseDefinitionUrl}, {@code callbackId}, 7.1's {@code localVariables} /
 * {@code handlerType} / {@code lockOwner}, the page's {@code sort}/{@code order}) is proven on the
 * record, not borrowed from Spring's lenient default.
 *
 * <p>CMMN is gated to 6.8+, so there is no pre-6.8 shape to pin. No executable-lane
 * ({@code cmmn-management/jobs}) document is recorded: the failing job leaves that lane within
 * milliseconds; it is the same row type as the dead-letter lane.
 */
class CmmnRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private static <T> T read(String fixture, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in = CmmnRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
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

    /* ============================ case instances ============================ */

    @Test
    void runtimeCaseOf68CarriesParentIdAndNoHistoricFields() {
        CaseInstanceRecord c = read("6.8/cmmn-runtime-case-instance.json", CaseInstanceRecord.class);

        assertThat(c.id()).isEqualTo("823c644c-e1fc-11f1-937e-6aebd179bc16");
        assertThat(c.caseDefinitionId()).isEqualTo("8239a52b-e1fc-11f1-937e-6aebd179bc16"); // bare uuid, no key:version
        assertThat(c.caseDefinitionName()).isEqualTo("Demo failing case");
        assertThat(c.businessKey()).isEqualTo("WIRE-474-A");
        assertThat(c.startTime()).isEqualTo("2026-11-16T20:30:05.820Z"); // verbatim string
        assertThat(c.startUserId()).isEqualTo("rest-admin");
        assertThat(c.state()).isEqualTo("active");
        assertThat(c.tenantId()).isEmpty(); // "" on the wire, not null
        assertThat(c.name()).isNull(); // explicit JSON null
        assertThat(c.parentId()).isNull();
        // The row serialises no definition key (the reader resolves it by id) and no historic field.
        assertThat(c.caseDefinitionKey()).isNull();
        assertThat(c.endTime()).isNull();
        assertThat(c.superProcessInstanceId()).isNull();
    }

    @Test
    void historicCaseOf68IsOpenWhileTheCaseRuns() {
        CaseInstanceRecord c = read("6.8/cmmn-historic-case-instance.json", CaseInstanceRecord.class);

        assertThat(c.id()).isEqualTo("823c644c-e1fc-11f1-937e-6aebd179bc16");
        assertThat(c.caseDefinitionId()).isEqualTo("8239a52b-e1fc-11f1-937e-6aebd179bc16");
        assertThat(c.startTime()).isEqualTo("2026-11-16T20:30:05.820Z");
        assertThat(c.state()).isEqualTo("active");
        assertThat(c.endTime()).isNull(); // the "still running" signal
        assertThat(c.superProcessInstanceId()).isNull();
        assertThat(c.parentId()).isNull(); // the historic row does not serialise parentId at all
    }

    @Test
    void terminatedHistoricCaseKeepsEndTimeAndState() {
        CaseInstanceRecord c68 = read("6.8/cmmn-historic-case-instance-terminated.json", CaseInstanceRecord.class);
        CaseInstanceRecord c71 = read("7.1/cmmn-historic-case-instance-terminated.json", CaseInstanceRecord.class);

        assertThat(c68.id()).isEqualTo("82416d64-e1fc-11f1-937e-6aebd179bc16");
        assertThat(c68.businessKey()).isEqualTo("WIRE-474-B");
        assertThat(c68.endTime()).isEqualTo("2026-11-16T20:30:26.479Z");
        assertThat(c68.state()).isEqualTo("terminated");

        assertThat(c71.id()).isEqualTo("82525d3f-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(c71.endTime()).isEqualTo("2026-11-16T20:30:26.809Z");
        assertThat(c71.state()).isEqualTo("terminated");
    }

    @Test
    void runtimeAndHistoricCaseOf71ReadTheSameWay() {
        CaseInstanceRecord runtime = read("7.1/cmmn-runtime-case-instance.json", CaseInstanceRecord.class);
        CaseInstanceRecord historic = read("7.1/cmmn-historic-case-instance.json", CaseInstanceRecord.class);

        assertThat(runtime.id()).isEqualTo("824bcd87-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(historic.id()).isEqualTo(runtime.id());
        assertThat(runtime.caseDefinitionId()).isEqualTo("824739a6-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(historic.caseDefinitionId()).isEqualTo(runtime.caseDefinitionId());
        assertThat(runtime.startTime()).isEqualTo("2026-11-16T20:30:05.921Z");
        assertThat(historic.startTime()).isEqualTo(runtime.startTime());
        assertThat(runtime.state()).isEqualTo("active");
        assertThat(historic.endTime()).isNull();
    }

    @Test
    void casePagesKeepTheTotalAndTypeEveryRow() {
        CaseInstancePage finished = read("6.8/cmmn-historic-case-instances-finished.json", CaseInstancePage.class);
        CaseInstancePage running = read("7.1/cmmn-runtime-case-instances.json", CaseInstancePage.class);

        assertThat(finished.total()).isEqualTo(1);
        assertThat(finished.dataOrEmpty()).hasSize(1);
        assertThat(finished.dataOrEmpty().get(0).id()).isEqualTo("82416d64-e1fc-11f1-937e-6aebd179bc16");
        assertThat(finished.dataOrEmpty().get(0).endTime()).isEqualTo("2026-11-16T20:30:26.479Z");
        assertThat(finished.dataOrEmpty().get(0).caseDefinitionId()).isEqualTo("8239a52b-e1fc-11f1-937e-6aebd179bc16");

        assertThat(running.total()).isEqualTo(1);
        assertThat(running.start()).isZero();
        assertThat(running.dataOrEmpty()).hasSize(1);
        assertThat(running.dataOrEmpty().get(0).id()).isEqualTo("824bcd87-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(running.dataOrEmpty().get(0).businessKey()).isEqualTo("WIRE-474-A");
    }

    /* ============================== plan items ============================== */

    @Test
    void planItemOf68KeepsInstanceIdShapeKeyAndDefinitionIdApart() {
        PlanItemPage page = read("6.8/cmmn-plan-item-instances.json", PlanItemPage.class);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.dataOrEmpty()).hasSize(2);
        PlanItemRecord svc = page.dataOrEmpty().get(0);
        assertThat(svc.id()).isEqualTo("823c644e-e1fc-11f1-937e-6aebd179bc16"); // the job join key
        assertThat(svc.elementId()).isEqualTo("planItem_svc"); // the DI shape key
        assertThat(svc.planItemDefinitionId()).isEqualTo("failingService"); // the migration pre-check key
        assertThat(svc.name()).isEqualTo("Failing service");
        assertThat(svc.planItemDefinitionType()).isEqualTo("servicetask");
        assertThat(svc.state()).isEqualTo("async-active");
        assertThat(svc.stage()).isFalse(); // a real false, not absent
        assertThat(svc.stageInstanceId()).isNull();
        assertThat(svc.createTime()).isEqualTo("2026-11-16T20:30:05.820Z");
        assertThat(svc.lastAvailableTime()).isEqualTo("2026-11-16T20:30:05.820Z");
        assertThat(svc.lastStartedTime()).isEqualTo("2026-11-16T20:30:05.821Z");
        assertThat(svc.lastEnabledTime()).isNull();
        assertThat(svc.completedTime()).isNull();
        assertThat(svc.occurredTime()).isNull();
        assertThat(svc.terminatedTime()).isNull();
        assertThat(svc.exitTime()).isNull();
        assertThat(svc.endedTime()).isNull(); // live: the pre-check counts it

        PlanItemRecord human = page.dataOrEmpty().get(1);
        assertThat(human.elementId()).isEqualTo("planItem_human");
        assertThat(human.planItemDefinitionId()).isEqualTo("keepAlive");
        assertThat(human.planItemDefinitionType()).isEqualTo("humantask");
        assertThat(human.state()).isEqualTo("active");
    }

    @Test
    void planItemOf71ToleratesItsExtraLocalVariables() {
        PlanItemPage page = read("7.1/cmmn-plan-item-instances.json", PlanItemPage.class);

        assertThat(page.total()).isEqualTo(2);
        PlanItemRecord svc = page.dataOrEmpty().get(0);
        assertThat(svc.id()).isEqualTo("824bcd89-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(svc.elementId()).isEqualTo("planItem_svc");
        assertThat(svc.planItemDefinitionId()).isEqualTo("failingService");
        assertThat(svc.state()).isEqualTo("async-active");
        assertThat(svc.stage()).isFalse();
        assertThat(svc.lastStartedTime()).isEqualTo("2026-11-16T20:30:05.922Z");
    }

    @Test
    void anEndedStagePlanItemKeepsItsStageFlagAndEndTimes() {
        PlanItemRecord stage = parse("""
                {"id":"pi-1","name":"Intake","elementId":"planItem_stage","planItemDefinitionId":"intakeStage",
                 "planItemDefinitionType":"stage","state":"completed","stage":true,"stageInstanceId":"pi-0",
                 "completedTime":"2026-11-16T21:00:00.000+01:00","endedTime":"2026-11-16T21:00:00.000+01:00",
                 "aFieldFromAFutureEngine":{"x":1}}
                """, PlanItemRecord.class);

        assertThat(stage.stage()).isTrue();
        assertThat(stage.stageInstanceId()).isEqualTo("pi-0");
        assertThat(stage.state()).isEqualTo("completed");
        assertThat(stage.completedTime()).isEqualTo("2026-11-16T21:00:00.000+01:00"); // offset form kept
        assertThat(stage.endedTime()).isEqualTo("2026-11-16T21:00:00.000+01:00");
        assertThat(stage.createTime()).isNull();
    }

    /* ================================= jobs ================================= */

    @Test
    void deadLetterJobOf68JoinsToItsPlanItemByInstanceId() {
        CmmnJobPage page = read("6.8/cmmn-deadletter-jobs.json", CmmnJobPage.class);
        CmmnJobRecord byId = read("6.8/cmmn-deadletter-job.json", CmmnJobRecord.class);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.dataOrEmpty()).hasSize(1);
        CmmnJobRecord job = page.dataOrEmpty().get(0);
        assertThat(job.id()).isEqualTo("823c8b61-e1fc-11f1-937e-6aebd179bc16");
        assertThat(job.caseInstanceId()).isEqualTo("823c644c-e1fc-11f1-937e-6aebd179bc16");
        assertThat(job.caseDefinitionId()).isEqualTo("8239a52b-e1fc-11f1-937e-6aebd179bc16");
        assertThat(job.planItemInstanceId()).isEqualTo("823c644e-e1fc-11f1-937e-6aebd179bc16"); // == plan item id
        assertThat(job.elementId()).isEqualTo("failingService"); // the DEFINITION id, not the shape key
        assertThat(job.elementName()).isEqualTo("Failing service");
        assertThat(job.retries()).isZero();
        assertThat(job.exceptionMessage())
                .isEqualTo("Unknown property used in expression: ${nonExistentBean.doStuff()}");
        assertThat(job.createTime()).isEqualTo("2026-11-16T20:30:43.495+00:00"); // 6.8's offset form, verbatim
        assertThat(job.dueDate()).isEqualTo("2026-11-16T20:30:53.495Z");
        assertThat(job.tenantId()).isEmpty();
        assertThat(byId).isEqualTo(job); // by-id hydration is the same row
    }

    @Test
    void deadLetterJobOf71ToleratesHandlerTypeAndLockFields() {
        CmmnJobPage page = read("7.1/cmmn-deadletter-jobs.json", CmmnJobPage.class);
        CmmnJobRecord byId = read("7.1/cmmn-deadletter-job.json", CmmnJobRecord.class);

        CmmnJobRecord job = page.dataOrEmpty().get(0);
        assertThat(job.id()).isEqualTo("824c1bac-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(job.caseInstanceId()).isEqualTo("824bcd87-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(job.caseDefinitionId()).isEqualTo("824739a6-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(job.planItemInstanceId()).isEqualTo("824bcd89-e1fc-11f1-acb8-7eebefdaa7d9");
        assertThat(job.elementId()).isEqualTo("failingService");
        assertThat(job.retries()).isZero();
        assertThat(job.exceptionMessage())
                .startsWith("Unknown property used in expression: ${nonExistentBean.doStuff()}");
        assertThat(job.createTime()).isEqualTo("2026-11-16T20:30:40.463Z");
        assertThat(byId).isEqualTo(job);
    }

    @Test
    void aJobWithoutCaseAttributionLeavesTheCaseFieldsNull() {
        // Measured on 7.1: a case-migration-status job passes ?scopeType=cmmn with no case attribution.
        CmmnJobRecord job = parse("""
                {"id":"00ee98c2","url":"http://e/cmmn-api/cmmn-management/deadletter-jobs/00ee98c2",
                 "caseInstanceId":null,"caseInstanceUrl":null,"caseDefinitionId":null,"caseDefinitionUrl":null,
                 "planItemInstanceId":null,"elementId":null,"elementName":null,
                 "handlerType":"case-migration-status","retries":0,"exceptionMessage":"batch is null",
                 "dueDate":"2026-11-12T18:58:40.000Z","createTime":"2026-11-12T18:58:31.715Z",
                 "lockOwner":null,"lockExpirationTime":null,"tenantId":""}
                """, CmmnJobRecord.class);

        assertThat(job.id()).isEqualTo("00ee98c2");
        assertThat(job.caseInstanceId()).isNull();
        assertThat(job.caseDefinitionId()).isNull();
        assertThat(job.planItemInstanceId()).isNull();
        assertThat(job.retries()).isZero();
    }

    @Test
    void aRowWithoutRetriesLeavesThemNullNotZero() {
        CmmnJobRecord job = parse("{\"id\":\"j\",\"caseInstanceId\":\"c\"}", CmmnJobRecord.class);

        assertThat(job.retries()).isNull();
        assertThat(job.exceptionMessage()).isNull();
    }

    /* ================================ pages ================================= */

    @Test
    void aPageWithoutDataIsAnEmptyListNotNull() {
        assertThat(new CaseInstancePage(null, 0, 0, 0).dataOrEmpty()).isEmpty();
        assertThat(new PlanItemPage(null, 0, 0, 0).dataOrEmpty()).isEmpty();
        assertThat(new CmmnJobPage(null, 0, 0, 0).dataOrEmpty()).isEmpty();
        assertThat(CaseInstancePage.empty().dataOrEmpty()).isEmpty();
        assertThat(PlanItemPage.empty().total()).isZero();
        assertThat(CmmnJobPage.empty().total()).isZero();
    }
}
