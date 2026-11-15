package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.client.DmnApiClient;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #474: the typed historic row keeps the participants' blank-starter rule (blank = no starter row). */
class InstanceParityStarterTest {

    private final EngineConfig engine = TestEngines.engine("a", "http://engine.test/flowable-rest/service");
    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final InstanceParityService service =
            new InstanceParityService(registry, flowable, mock(DmnApiClient.class));

    private InstanceParityService.ParticipantPage participantsWithStarter(String startUserId) {
        when(registry.require("a")).thenReturn(engine);
        when(flowable.getHistoricProcessInstance(any(), any(), any()))
                .thenReturn(ProcessInstanceRecords.instance(
                        Map.of("id", "pi-1", "endTime", "2026-11-15T00:00:00Z", "startUserId", startUserId)));
        when(flowable.listHistoricIdentityLinks(any(), any(), any())).thenReturn(List.of());
        return service.participants("a", "pi-1");
    }

    @Test
    void blankStartUserIdAddsNoStarterRow() {
        assertThat(participantsWithStarter("  ").rows()).isEmpty();
    }

    @Test
    void realStartUserIdIsTheStarterRow() {
        assertThat(participantsWithStarter("kermit").rows())
                .containsExactly(new InstanceParityService.ParticipantRow("kermit", "starter", false));
    }
}
