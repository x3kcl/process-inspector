package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.CaseMigrationClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rung 1: the case cohort filter (#500) and its 7.x gate. */
class CaseCohortServiceTest {

    private static final String ENGINE = "engine-x";

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final CaseMigrationClient cases = mock(CaseMigrationClient.class);
    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private CaseCohortService service;

    @BeforeEach
    void setUp() {
        service = new CaseCohortService(registry, cases);
        when(registry.require(ENGINE)).thenReturn(engine);
    }

    private void engineVersion(String version, boolean scopeType) {
        when(registry.healthOf(ENGINE))
                .thenReturn(new EngineHealth(
                        true,
                        version,
                        null,
                        0L,
                        new EngineCapabilities(true, true, scopeType, scopeType, true),
                        null,
                        null,
                        null));
    }

    private static FlowablePage page(int rows, long total) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(Map.of("id", "case-" + i));
        }
        return new FlowablePage(data, total, 0, rows);
    }

    @Test
    void anExactBusinessKeyAndAStartRangeGoToTheEngineOnSixEight() {
        engineVersion("6.8.0", true);
        when(cases.listRunningCases(
                        engine,
                        CallPriority.INTERACTIVE,
                        "def-1",
                        "ORDER-7",
                        null,
                        "2026-11-01T00:00:00Z",
                        null,
                        CaseCohortService.CAP))
                .thenReturn(page(2, 2));

        CaseCohortPreviewResponse preview = service.preview(
                ENGINE, new CaseCohortRequest("def-1", "def-2", " ORDER-7 ", false, "2026-11-01T00:00:00Z", " "));

        assertThat(preview.count()).isEqualTo(2);
        assertThat(preview.truncated()).isFalse();
        assertThat(preview.cap()).isEqualTo(500);
        assertThat(preview.strategy()).isEqualTo("per-case");
        assertThat(service.listCases(
                                ENGINE,
                                new CaseCohortRequest("def-1", "def-2", "ORDER-7", null, "2026-11-01T00:00:00Z", null))
                        .caseIds())
                .containsExactly("case-0", "case-1");
    }

    @Test
    void aContainsFilterIsRefusedOnSixEightBeforeTheEngineAndSentAsLikeOnSeven() {
        engineVersion("6.8.0", true);
        assertThatThrownBy(() ->
                        service.preview(ENGINE, new CaseCohortRequest("def-1", "def-2", "ORDER", true, null, null)))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("version-gate");
                    assertThat(e.getMessage()).contains("6.8.0").contains("businessKeyLike");
                });
        verifyNoInteractions(cases);

        engineVersion("7.1.0", true);
        when(cases.listRunningCases(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(page(1, 1));
        service.preview(ENGINE, new CaseCohortRequest("def-1", "def-2", "ORDER", true, null, null));
        verify(cases)
                .listRunningCases(
                        engine, CallPriority.INTERACTIVE, "def-1", null, "%ORDER%", null, null, CaseCohortService.CAP);
    }

    @Test
    void aCohortWiderThanTheCapIsTruncatedAndALowerBound() {
        engineVersion("7.1.0", true);
        when(cases.listRunningCases(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(page(500, 812));

        assertThat(service.preview(ENGINE, new CaseCohortRequest("def-1", "def-2", null, null, null, null))
                        .truncated())
                .isTrue();
        assertThat(service.listCases(ENGINE, new CaseCohortRequest("def-1", "def-2", null, null, null, null))
                        .lowerBound())
                .isTrue();
    }

    @Test
    void aPreSixEightEngineAndAMissingSourceAreRefusedBeforeTheEngine() {
        engineVersion("6.3.1", false);
        assertThatThrownBy(
                        () -> service.preview(ENGINE, new CaseCohortRequest("def-1", "def-2", null, null, null, null)))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("capability-unavailable"));
        engineVersion("6.8.0", true);
        assertThatThrownBy(() -> service.preview(ENGINE, new CaseCohortRequest(" ", "def-2", null, null, null, null)))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("missing-field"));
        verifyNoInteractions(cases);
    }
}
