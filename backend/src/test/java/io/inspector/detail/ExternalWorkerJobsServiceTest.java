package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.FlowableEngineClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the CAPABILITY GATE on the fifth job queue (v1.x #7). The BFF
 * is the real gate — a pre-6.8 engine is refused with a ProblemDetail before any call leaves,
 * never a masking empty list and never a confusing 404 from the sibling external-job-api
 * context. The read-over-real-wire proof (fetch + lock-owner mapping) is the rung-4
 * ExternalWorkerJobIT; here the client is mocked to prove the gate ordering.
 */
class ExternalWorkerJobsServiceTest {

    private static final String ENGINE = "e";

    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private final FlowableEngineClient flowable = mock(FlowableEngineClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final InstanceDetailService service =
            new InstanceDetailService(registry, flowable, new InspectorProperties(null, null, null, null, List.of()));

    private void health(EngineCapabilities capabilities) {
        when(registry.require(ENGINE)).thenReturn(engine);
        when(registry.healthOf(ENGINE))
                .thenReturn(new EngineHealth(true, "?", null, 0L, capabilities, null, null, null));
    }

    @Test
    void unprobedEngineIsRefusedNotSentBlind() {
        health(null); // no successful probe yet → capabilities unknown

        assertThatThrownBy(() -> service.externalWorkerJobs(ENGINE, "pi-1"))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unknown"));
        verify(flowable, never()).listExternalWorkerJobs(any(), any(), anyInt(), anyInt());
    }

    @Test
    void preSixEightEngineIsRefusedWithUnsupportedVersion() {
        // changeState/migration present, externalWorkerJobs absent — a 6.5/6.6-era engine.
        health(new EngineCapabilities(true, true, false, false, true));

        assertThatThrownBy(() -> service.externalWorkerJobs(ENGINE, "pi-1"))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unavailable"));
        verify(flowable, never()).listExternalWorkerJobs(any(), any(), anyInt(), anyInt());
    }
}
