package io.inspector.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.client.FlowableEngineClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.RegistryProperties;
import io.inspector.config.RegistryProperties.Source;
import io.inspector.registry.EngineRegistryRow;
import io.inspector.registry.EngineRegistryStore;
import io.inspector.registry.RegistryUrlValidator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 (Gemini S4 review): the probe is a live DIAL surface, so it MUST re-validate the base-URL
 * before dialling — closing the validated-at-add-then-rebound-to-internal window. A row whose URL
 * now resolves internal is refused WITHOUT touching {@link FlowableEngineClient#engineInfo}.
 */
class AdminEnginesProbeTest {

    private static EngineRegistryRow row(String id, String baseUrl, String env) {
        EngineRegistryRow r = new EngineRegistryRow();
        r.setId(id);
        r.setBaseUrl(baseUrl);
        r.setEnvironment(env);
        r.setMode("read-only");
        r.setLifecycle("draft");
        r.setAuthType("none");
        return r;
    }

    private AdminEnginesController controller(EngineRegistryStore store, FlowableEngineClient flowable) {
        RegistryProperties props =
                new RegistryProperties(Source.DB, List.of("93.184.216.0/24", "127.0.0.0/8"), Set.of());
        return new AdminEnginesController(
                store,
                mock(InspectorProperties.class),
                props,
                new RegistryUrlValidator(),
                flowable,
                mock(Environment.class));
    }

    @Test
    void probe_refuses_to_dial_a_base_url_that_now_resolves_internal() {
        EngineRegistryStore store = mock(EngineRegistryStore.class);
        FlowableEngineClient flowable = mock(FlowableEngineClient.class);
        Authentication auth = mock(Authentication.class);
        // The row was validated at add, but its URL now points at the metadata IP (rebinding).
        when(store.requireRow("e")).thenReturn(row("e", "http://169.254.169.254/", "test"));
        when(store.recordProbe(eq("e"), eq(false), any(), any()))
                .thenReturn(row("e", "http://169.254.169.254/", "test"));

        controller(store, flowable).probe("e", auth);

        verify(flowable, never()).engineInfo(any()); // NEVER dialled
        verify(store).recordProbe(eq("e"), eq(false), any(), any()); // recorded as a failed probe
    }

    @Test
    void probe_dials_an_allowlisted_public_url() {
        EngineRegistryStore store = mock(EngineRegistryStore.class);
        FlowableEngineClient flowable = mock(FlowableEngineClient.class);
        Authentication auth = mock(Authentication.class);
        when(store.requireRow("e")).thenReturn(row("e", "https://93.184.216.34/service", "test"));
        when(flowable.engineInfo(any())).thenReturn(java.util.Map.of("version", "6.8.0"));
        when(store.recordProbe(eq("e"), eq(true), any(), any()))
                .thenReturn(row("e", "https://93.184.216.34/service", "test"));

        controller(store, flowable).probe("e", auth);

        verify(flowable).engineInfo(any()); // dialled — it is allowlisted + public
        verify(store).recordProbe(eq("e"), eq(true), any(), any());
    }
}
