package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.ProcessApiClient;
import io.inspector.cmmn.CaseDetailService;
import io.inspector.cmmn.CmmnScopeService;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
import io.inspector.dto.NearestSiblingResponse;
import io.inspector.sibling.SiblingDiffService;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1 (unit-test-patterns) regression for #252: extends #248's "the detail READ surface must
 * survive a registry disable" fix to the three read-only services #251 deliberately left on
 * {@link EngineRegistry#require} — {@link SiblingDiffService} (the Compare tab),
 * {@link CaseDetailService} (CMMN case detail), and {@link CmmnScopeService} (the CMMN
 * out-of-scope drill). All three now resolve their target engine via
 * {@link EngineRegistry#resolveOrNotFound}, which — like {@link EngineRegistry#resolve} —
 * returns a registered-but-disabled row instead of 404ing "Unknown engine". Uses a REAL
 * {@code EngineRegistry} over an {@code enabled=false} config so the disabled semantics are the
 * actual ones, not a stub's.
 */
class DisabledEngineExtendedReadTest {

    private static final String DISABLED_ENGINE = "engine-c";

    private final EngineRegistry registry = new EngineRegistry(new InspectorProperties(
            null,
            null,
            null,
            null,
            List.of(TestEngines.builder(DISABLED_ENGINE, "http://engine-c.test/flowable-rest/service")
                    .enabled(false)
                    .build())));

    @Test
    void resolveOrNotFoundReturnsTheDisabledRowInsteadOfRefusingIt() {
        assertThat(registry.resolveOrNotFound(DISABLED_ENGINE)).isNotNull();
        // ...while require() (the operable-target lookup mutations still use) keeps refusing it.
        assertThatThrownBy(() -> registry.require(DISABLED_ENGINE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown engine: " + DISABLED_ENGINE);
    }

    @Test
    void aGenuinelyUnknownEngineStill404sOutOfResolveOrNotFound() {
        assertThatThrownBy(() -> registry.resolveOrNotFound("engine-zzz"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(404);
                    assertThat(rse.getReason()).isEqualTo("Unknown engine: engine-zzz");
                });
    }

    @Test
    void siblingDiffNearestSiblingOnADisabledEngineRendersInsteadOf404ing() {
        ProcessApiClient flowable = mock(ProcessApiClient.class);
        InstanceDetailService detail = mock(InstanceDetailService.class);
        EngineConfig engine = registry.resolveOrNotFound(DISABLED_ENGINE);
        when(detail.requireHistoric(engine, "pi-1"))
                .thenReturn(Map.of("id", "pi-1", "processDefinitionId", "invoice:2:def-uuid"));
        when(flowable.queryHistoricProcessInstances(eq(engine), any(), any())).thenReturn(FlowablePage.empty());

        NearestSiblingResponse res =
                new SiblingDiffService(registry, flowable, detail).nearestSibling(DISABLED_ENGINE, "pi-1");

        // The engine is correctly identified — never "Unknown engine" for a registered row.
        assertThat(res.found()).isFalse(); // no sibling seeded — this only proves it didn't 404 first
        assertThat(res.processDefinitionKey()).isEqualTo("invoice");
    }

    /**
     * CaseDetailService/CmmnScopeService gate on a SEPARATE, honest concern after the registry
     * seam — {@code CmmnCapabilities.requireScopeType}, which refuses with "capability-unknown"
     * when the engine has never been health-probed (its own {@code health.capabilities()} is
     * null by default, {@link EngineHealth#unknown()}). A disabled test engine has no probe
     * history here, so reaching THAT refusal (a {@link GuardRefusedException} CONFLICT) instead
     * of the registry's 404 "Unknown engine" is exactly the proof the fix survived the disable —
     * the two gates are deliberately independent and this test does not need to satisfy the
     * second one to prove the first no longer blocks.
     */
    @Test
    void caseDetailVitalsOnADisabledEngineReachesTheCapabilityGateInsteadOf404ing() {
        CmmnApiClient flowable = mock(CmmnApiClient.class);

        assertThatThrownBy(() -> new CaseDetailService(registry, flowable).vitals(DISABLED_ENGINE, "case-1"))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(ex -> {
                    GuardRefusedException gre = (GuardRefusedException) ex;
                    assertThat(gre.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(gre.code()).isEqualTo("capability-unknown");
                });
    }

    @Test
    void cmmnScopeOutOfScopeDeadLettersOnADisabledEngineReachesTheCapabilityGateInsteadOf404ing() {
        CmmnApiClient flowable = mock(CmmnApiClient.class);

        assertThatThrownBy(() -> new CmmnScopeService(registry, flowable).outOfScopeDeadLetters(DISABLED_ENGINE))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(ex -> {
                    GuardRefusedException gre = (GuardRefusedException) ex;
                    assertThat(gre.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(gre.code()).isEqualTo("capability-unknown");
                });
    }
}
