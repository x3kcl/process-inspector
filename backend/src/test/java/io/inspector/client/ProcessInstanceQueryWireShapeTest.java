package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.client.ProcessInstanceQuery.VariableFilter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the QUERY request body (#720): what {@link ProcessInstanceQuery} — the one
 * body of POST /query/historic-process-instances and POST /query/process-instances — serialises
 * to. The rule every case states: a key the caller did not set is ABSENT, never {@code null}
 * (flowable-rest reads an explicit null on several query fields as a filter of its own), while a
 * value the caller did set goes out as it is — {@code false}, {@code 0}, an empty id list, a
 * variable filter whose VALUE is null. What each call site puts into the body is pinned where the
 * site is driven ({@code SearchQueryBodyTest}, {@code StageZeroQueryBodyTest},
 * {@code CohortQueryBodyTest}, {@code RunningCountQueryBodyTest}).
 */
class ProcessInstanceQueryWireShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aQueryWithNothingSetIsAnEmptyObject() {
        assertThat(json(ProcessInstanceQuery.builder().build())).isEqualTo("{}");
    }

    @Test
    void aCountProbeCarriesItsOnePredicateAndSizeOneAndNoOtherKey() {
        assertThat(json(ProcessInstanceQuery.builder().suspended(false).size(1).build()))
                .isEqualTo("{\"suspended\":false,\"size\":1}");
        assertThat(json(ProcessInstanceQuery.builder().finished(true).size(1).build()))
                .isEqualTo("{\"finished\":true,\"size\":1}");
    }

    @Test
    void everyScalarFilterPagingAndSortKeyGoesOutUnderItsFlowableName() {
        ProcessInstanceQuery query = ProcessInstanceQuery.builder()
                .processDefinitionId("demoOrder:3:v3")
                .processDefinitionKey("demoOrder")
                .processBusinessKey("ORD-1")
                .processBusinessKeyLike("%ORD%")
                .superProcessInstanceId("parent-1")
                .processInstanceIds(List.of("pi-1", "pi-2"))
                .finished(false)
                .suspended(true)
                .startedAfter("2026-11-01T00:00:00Z")
                .startedBefore("2026-11-17T00:00:00Z")
                .tenantId("acme")
                .start(50)
                .size(25)
                .sort("startTime", "desc")
                .build();

        assertThat(json(query))
                .isEqualTo("{\"processDefinitionId\":\"demoOrder:3:v3\",\"processDefinitionKey\":\"demoOrder\","
                        + "\"processBusinessKey\":\"ORD-1\",\"processBusinessKeyLike\":\"%ORD%\","
                        + "\"superProcessInstanceId\":\"parent-1\",\"processInstanceIds\":[\"pi-1\",\"pi-2\"],"
                        + "\"finished\":false,\"suspended\":true,\"startedAfter\":\"2026-11-01T00:00:00Z\","
                        + "\"startedBefore\":\"2026-11-17T00:00:00Z\",\"tenantId\":\"acme\",\"start\":50,"
                        + "\"size\":25,\"sort\":\"startTime\",\"order\":\"desc\"}");
    }

    @Test
    void aVariableFilterKeepsALongANumberAStringAStringAndOmitsOnlyAnUnsetType() {
        ProcessInstanceQuery query = ProcessInstanceQuery.builder()
                .variables(List.of(
                        new VariableFilter("amount", 9007199254740993L, "greaterThan", "long"),
                        new VariableFilter("orderNo", "42", "equals", null),
                        new VariableFilter("approved", true, "notEquals", "boolean")))
                .size(10)
                .build();

        assertThat(json(query))
                .isEqualTo("{\"variables\":["
                        + "{\"name\":\"amount\",\"value\":9007199254740993,\"operation\":\"greaterThan\",\"type\":\"long\"},"
                        + "{\"name\":\"orderNo\",\"value\":\"42\",\"operation\":\"equals\"},"
                        + "{\"name\":\"approved\",\"value\":true,\"operation\":\"notEquals\",\"type\":\"boolean\"}],"
                        + "\"size\":10}");
    }

    @Test
    void aVariableFilterWithANullValueSendsTheNullBecauseTheMapBodySentIt() {
        ProcessInstanceQuery query = ProcessInstanceQuery.builder()
                .variables(List.of(new VariableFilter("note", null, "equals", null)))
                .build();

        assertThat(json(query))
                .isEqualTo("{\"variables\":[{\"name\":\"note\",\"value\":null,\"operation\":\"equals\"}]}");
    }

    @Test
    void anEmptyIdListIsSentAsAnEmptyListAndAnUnsetOneIsLeftOut() {
        // An empty processInstanceIds is its own question to the engine; the body never decides
        // between "match nothing" and "no filter" — callers that must not ask return before building.
        assertThat(json(ProcessInstanceQuery.builder()
                        .processInstanceIds(List.of())
                        .size(0)
                        .build()))
                .isEqualTo("{\"processInstanceIds\":[],\"size\":0}");
        assertThat(json(ProcessInstanceQuery.builder().size(0).build())).isEqualTo("{\"size\":0}");
    }

    @Test
    void aBlankOrMissingTenantLeavesTheTenantKeyOut() {
        assertThat(json(ProcessInstanceQuery.builder().tenantId(null).size(1).build()))
                .isEqualTo("{\"size\":1}");
        assertThat(json(ProcessInstanceQuery.builder().tenantId("  ").size(1).build()))
                .isEqualTo("{\"size\":1}");
        assertThat(json(ProcessInstanceQuery.builder().tenantId("acme").size(1).build()))
                .isEqualTo("{\"tenantId\":\"acme\",\"size\":1}");
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }
}
