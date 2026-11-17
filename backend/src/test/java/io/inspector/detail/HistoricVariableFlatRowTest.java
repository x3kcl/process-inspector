package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.ExternalJobApiClient;
import io.inspector.client.HistoricVariablePage;
import io.inspector.client.HistoricVariableRecord;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceVariables;
import io.inspector.dto.InstanceVariables.VariableDto;
import io.inspector.dto.SiblingDiffResponse;
import io.inspector.dto.SiblingDiffResponse.VariableChange;
import io.inspector.dto.SiblingDiffResponse.VariableDelta;
import io.inspector.registry.EngineRegistry;
import io.inspector.sibling.SiblingDiffService;
import io.inspector.support.TestEngines;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #474 — the historic variable readers stay behaviour-identical to the untyped map readers they
 * replaced. Before the typing, a historic row WITHOUT a nested {@code variable} was read from the
 * row itself (name / type / value on the row); that flat row must still render in the variable
 * ledger and in the sibling diff, not be silently dropped.
 *
 * <p>The recorded Flowable 6.3.1 document ({@code wire/6.3/}, one throwaway demoUserTask instance)
 * nests the envelope like 6.8 and 7.1, with two 6.3 differences pinned here: the row carries NO
 * {@code executionId} key and the nested {@code scope} is JSON null (6.8+ answer global/local).
 */
class HistoricVariableFlatRowTest {

    private static final ObjectMapper STRICT = new ObjectMapper();
    private static final long BEYOND_DOUBLE = 9007199254740993L; // 2^53 + 1
    private static final String FLAT_ROW_PAGE = """
            {"data":[{"id":"h1","processInstanceId":"pi-1","executionId":"pi-1","taskId":null,
                      "name":"amount","type":"integer","value":100}],
             "total":1,"start":0,"size":1}
            """;
    private static final String NESTED_ROW_PAGE = """
            {"data":[{"id":"h2","processInstanceId":"pi-2","executionId":"pi-2","taskId":null,
                      "variable":{"name":"amount","scope":"global","type":"integer","value":250}}],
             "total":1,"start":0,"size":1}
            """;

    private final EngineConfig engine = TestEngines.engine("engine-a", "http://engine-a");
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final InstanceDetailService detail = new InstanceDetailService(
            registry,
            flowable,
            mock(ExternalJobApiClient.class),
            new InspectorProperties(null, null, null, null, List.of()),
            mock(ProtectedInstanceRepository.class));

    private static HistoricVariablePage page(String json) throws IOException {
        return STRICT.readValue(json, HistoricVariablePage.class);
    }

    private static HistoricVariablePage fixture(String path) throws IOException {
        try (InputStream in = HistoricVariableFlatRowTest.class.getResourceAsStream("/wire/" + path)) {
            if (in == null) throw new IOException("missing fixture wire/" + path);
            return STRICT.readValue(in, HistoricVariablePage.class);
        }
    }

    private void completed(String instanceId, HistoricVariablePage variables) {
        when(registry.resolve("engine-a")).thenReturn(Optional.of(engine));
        when(registry.resolveOrNotFound("engine-a")).thenReturn(engine);
        when(flowable.getHistoricProcessInstance(eq(engine), any(), eq(instanceId)))
                .thenReturn(ProcessInstanceRecords.instance(Map.of(
                        "id", instanceId,
                        "processDefinitionId", "payment:3:abc",
                        "startTime", "2026-07-07T07:00:00Z",
                        "endTime", "2026-07-07T08:00:00Z")));
        when(flowable.listHistoricVariableInstances(eq(engine), any(), eq(instanceId), anyInt()))
                .thenReturn(variables);
    }

    /* ================= the recorded 6.3.1 document ================= */

    @Test
    void flowable631_nestsTheEnvelope_withoutExecutionId_andWithANullScope() throws IOException {
        HistoricVariablePage page = fixture("6.3/historic-variable-instances-typed.json");

        assertThat(page.total()).isEqualTo(11);
        assertThat(page.dataOrEmpty()).hasSize(11);
        assertThat(page.dataOrEmpty()).allSatisfy(row -> {
            assertThat(row.variable()).isNotNull();
            assertThat(row.executionId()).isNull(); // 6.3 sends no executionId key at all
            assertThat(row.variable().scope()).isNull(); // 6.8+ say global/local
        });
        Map<String, HistoricVariableRecord> byName = page.dataOrEmpty().stream()
                .collect(java.util.stream.Collectors.toMap(r -> r.variable().name(), r -> r));
        assertThat(byName.get("aLong").variable().type()).isEqualTo("long");
        assertThat(byName.get("aLong").variable().value())
                .isInstanceOf(Long.class)
                .isEqualTo(BEYOND_DOUBLE);
        assertThat(byName.get("aDate").variable().value()).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(byName.get("aNull").variable().type()).isNull();
        assertThat(byName.get("aNull").variable().value()).isNull();
        assertThat(byName.get("aBinary").variable().valueUrl()).endsWith("/data");
    }

    @Test
    void flowable631_historicLedgerRendersEveryRecordedVariable() throws IOException {
        completed("pi-63", fixture("6.3/historic-variable-instances-typed.json"));

        InstanceVariables ledger = detail.variables("engine-a", "pi-63");

        assertThat(ledger.source()).isEqualTo("HISTORIC");
        assertThat(ledger.processVariables().stream().map(VariableDto::name))
                .containsExactly(
                        "aBinary",
                        "aBoolean",
                        "aDate",
                        "aDouble",
                        "aLong",
                        "aNull",
                        "aSmallLong",
                        "aString",
                        "anInteger",
                        "stepLong",
                        "stepNull");
    }

    /* ================= a flat row (the pre-typing fallback) ================= */

    @Test
    void flatHistoricRow_isReadFromTheRowItself_inTheVariableLedger() throws IOException {
        completed("pi-1", page(FLAT_ROW_PAGE));

        InstanceVariables ledger = detail.variables("engine-a", "pi-1");

        assertThat(ledger.processVariables())
                .containsExactly(new VariableDto("amount", "integer", 100, false, null, "global", "pi-1", null));
    }

    @Test
    void flatHistoricRow_isReadFromTheRowItself_inTheSiblingDiff() throws IOException {
        completed("pi-1", page(FLAT_ROW_PAGE));
        completed("pi-2", page(NESTED_ROW_PAGE));
        when(flowable.listHistoricActivities(eq(engine), any(), any(), anyInt(), anyInt()))
                .thenReturn(io.inspector.client.ExecutionRecords.activityPage(List.of(), 0));

        SiblingDiffResponse res = new SiblingDiffService(registry, flowable, detail).diff("engine-a", "pi-1", "pi-2");

        assertThat(res.variables()).hasSize(1);
        VariableDelta amount = res.variables().get(0);
        assertThat(amount.name()).isEqualTo("amount");
        assertThat(amount.change()).isEqualTo(VariableChange.CHANGED); // 100 vs 250, not ONLY_IN_SIBLING
    }
}
