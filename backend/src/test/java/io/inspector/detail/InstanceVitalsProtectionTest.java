package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowableEngineClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceDetail;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the R-SAFE-05 point-of-action read the vitals header consumes
 * (usability W3 sliver). The enforcement lives in {@code CorrectiveActionService} and is proven
 * elsewhere; this pins only that {@code vitals()} SURFACES the protected state — true + reason
 * when registered, false when not, and null (unknown, never a failed render) on a store outage.
 * The instance is ended so the derivation needs no runtime/lane calls — the protection read is
 * what is under test. Uses an ended historic row so no engine wire is exercised.
 */
class InstanceVitalsProtectionTest {

    private static final String ENGINE = "e";
    private static final String INSTANCE = "pi-1";

    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private final FlowableEngineClient flowable = mock(FlowableEngineClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private final InstanceDetailService service = new InstanceDetailService(
            registry, flowable, new InspectorProperties(null, null, null, null, List.of()), protectedInstances);

    private void endedInstanceExists() {
        when(registry.require(ENGINE)).thenReturn(engine);
        when(flowable.getHistoricProcessInstance(engine, INSTANCE))
                .thenReturn(Map.of("id", INSTANCE, "endTime", "2026-07-10T09:05:00Z"));
    }

    @Test
    void vitalsSurfacesProtectedStateAndReasonWhenRegistered() {
        endedInstanceExists();
        when(protectedInstances.findById(new ProtectedInstance.Key(ENGINE, INSTANCE)))
                .thenReturn(Optional.of(new ProtectedInstance(
                        ENGINE, INSTANCE, "litigation hold — do not touch", "admin", Instant.now())));

        InstanceDetail vitals = service.vitals(ENGINE, INSTANCE);

        assertThat(vitals.protectedInstance()).isTrue();
        assertThat(vitals.protectionReason()).isEqualTo("litigation hold — do not touch");
    }

    @Test
    void vitalsReportsUnprotectedAsFalseWithNoReason() {
        endedInstanceExists();
        when(protectedInstances.findById(new ProtectedInstance.Key(ENGINE, INSTANCE)))
                .thenReturn(Optional.empty());

        InstanceDetail vitals = service.vitals(ENGINE, INSTANCE);

        assertThat(vitals.protectedInstance()).isFalse();
        assertThat(vitals.protectionReason()).isNull();
    }

    @Test
    void aProtectionStoreOutageDegradesToUnknownNeverAFailedRender() {
        endedInstanceExists();
        when(protectedInstances.findById(new ProtectedInstance.Key(ENGINE, INSTANCE)))
                .thenThrow(new RuntimeException("postgres down"));

        InstanceDetail vitals = service.vitals(ENGINE, INSTANCE);

        // null = unknown: the badge/gate stay optimistic client-side; the execution guard is the gate.
        assertThat(vitals.protectedInstance()).isNull();
        assertThat(vitals.protectionReason()).isNull();
    }
}
