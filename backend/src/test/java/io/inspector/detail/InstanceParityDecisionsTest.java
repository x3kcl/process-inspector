package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.client.DecisionExecutionPage;
import io.inspector.client.DecisionExecutionRecord;
import io.inspector.client.DmnApiClient;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceParityService.DecisionPage;
import io.inspector.detail.InstanceParityService.DecisionRow;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #474: the Decisions tab reads the typed DMN history row exactly as it read the map. */
class InstanceParityDecisionsTest {

    private final EngineConfig engine = TestEngines.engine("a", "http://engine.test/flowable-rest/service");
    private final DmnApiClient dmn = mock(DmnApiClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final InstanceParityService service =
            new InstanceParityService(registry, mock(ProcessApiClient.class), dmn);

    @BeforeEach
    void setUp() {
        when(registry.require("a")).thenReturn(engine);
        when(registry.healthOf("a")).thenReturn(EngineHealth.unreachable("no probe yet", 0L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void recordedExecutionBecomesOneDecisionRow(String major) throws IOException {
        DecisionExecutionPage page;
        try (InputStream in =
                getClass().getResourceAsStream("/wire/" + major + "/dmn-historic-decision-executions.json")) {
            page = new ObjectMapper().readValue(in, DecisionExecutionPage.class);
        }
        when(dmn.listByProcessInstance(eq(engine), any(), eq("pi-1"), anyInt())).thenReturn(page);
        DecisionExecutionRecord recorded = page.dataOrEmpty().get(0);

        DecisionPage decisions = service.decisions("a", "pi-1");

        assertThat(decisions.available()).isTrue();
        assertThat(decisions.capped()).isFalse();
        assertThat(decisions.rows())
                .containsExactly(new DecisionRow(
                        recorded.id(), "rec474Decision", "decide", false, recorded.startTime(), recorded.endTime()));
    }

    @Test
    void olderKeyNameIsTheFallbackAndAShortPageIsCapped() {
        when(dmn.listByProcessInstance(eq(engine), any(), eq("pi-1"), anyInt()))
                .thenReturn(new DecisionExecutionPage(
                        List.of(
                                new DecisionExecutionRecord("d1", " ", "legacyKey", "decide", true, "t0", null),
                                new DecisionExecutionRecord("d2", null, null, null, null, null, null)),
                        3,
                        0,
                        2));

        DecisionPage decisions = service.decisions("a", "pi-1");

        assertThat(decisions.capped()).isTrue(); // total 3 > 2 rows
        assertThat(decisions.rows())
                .containsExactly(
                        new DecisionRow("d1", "legacyKey", "decide", true, "t0", null),
                        new DecisionRow("d2", null, null, false, null, null)); // absent `failed` reads false
    }

    @Test
    void noDmnEngineIsAnAbsentPage() {
        when(dmn.listByProcessInstance(eq(engine), any(), eq("pi-1"), anyInt())).thenReturn(null);

        assertThat(service.decisions("a", "pi-1")).isEqualTo(new DecisionPage(false, List.of(), false));
    }
}
