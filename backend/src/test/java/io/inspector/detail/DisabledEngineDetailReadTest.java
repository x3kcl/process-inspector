package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.ExternalJobApiClient;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.InstanceDetail;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1 (unit-test-patterns) regression for #248: the detail READ surface must survive a
 * registry disable. A registered-but-disabled engine used to 404 "Unknown engine" out of
 * {@code vitals()} (via {@link EngineRegistry#require}) while the SAME page's audit/notes tab —
 * which never consults the registry — rendered that engine's full history one scroll below.
 * Reads now resolve via {@link EngineRegistry#resolve}, which returns disabled rows; only a
 * genuinely unknown/removed id 404s. Uses a REAL {@code EngineRegistry} over an
 * {@code enabled=false} config so the disabled semantics are the actual ones, not a stub's.
 */
class DisabledEngineDetailReadTest {

    private static final String DISABLED_ENGINE = "engine-c";
    private static final String INSTANCE = "pi-1";

    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final EngineRegistry registry = new EngineRegistry(new InspectorProperties(
            null,
            null,
            null,
            null,
            List.of(TestEngines.builder(DISABLED_ENGINE, "http://engine-c.test/flowable-rest/service")
                    .enabled(false)
                    .build())));
    private final InstanceDetailService service = new InstanceDetailService(
            registry,
            flowable,
            mock(ExternalJobApiClient.class),
            new InspectorProperties(null, null, null, null, List.of()),
            mock(ProtectedInstanceRepository.class));

    /** The fixture really is the bug's shape: an operable-target lookup still refuses this engine. */
    @Test
    void theDisabledEngineIsNotAnOperableTarget() {
        assertThatThrownBy(() -> registry.require(DISABLED_ENGINE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown engine: " + DISABLED_ENGINE);
        // ...but it still resolves for reads/display — the seam vitals() now sits on.
        assertThat(registry.resolve(DISABLED_ENGINE)).isPresent();
    }

    @Test
    void vitalsOnADisabledButRegisteredEngineRendersInsteadOf404ing() {
        when(flowable.getHistoricProcessInstance(
                        registry.resolve(DISABLED_ENGINE).orElseThrow(), CallPriority.INTERACTIVE, INSTANCE))
                .thenReturn(Map.of(
                        "id", INSTANCE,
                        "processDefinitionId", "invoice:3:def-uuid",
                        "startTime", "2026-07-10T09:00:00Z",
                        "endTime", "2026-07-10T09:05:00Z"));

        InstanceDetail vitals = service.vitals(DISABLED_ENGINE, INSTANCE);

        // The engine is correctly identified — never "Unknown engine" for a registered row.
        assertThat(vitals.compositeId()).isEqualTo(DISABLED_ENGINE + ":" + INSTANCE);
        assertThat(vitals.engineId()).isEqualTo(DISABLED_ENGINE);
        assertThat(vitals.processInstanceId()).isEqualTo(INSTANCE);
        assertThat(vitals.definitionKey()).isEqualTo("invoice");
    }

    @Test
    void aGenuinelyUnknownEngineStill404sWithUnknownEngine() {
        assertThatThrownBy(() -> service.vitals("engine-zzz", INSTANCE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(404);
                    assertThat(rse.getReason()).isEqualTo("Unknown engine: engine-zzz");
                });
        Optional<?> resolved = registry.resolve("engine-zzz");
        assertThat(resolved).isEmpty();
    }
}
