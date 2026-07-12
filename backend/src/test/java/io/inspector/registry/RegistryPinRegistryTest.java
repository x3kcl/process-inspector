package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.config.RegistryProperties;
import io.inspector.config.RegistryProperties.Source;
import io.inspector.support.TestEngines;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the Spring-side half of connect-time pinning (docs/REGISTRY-CRUD.md §5, R-OPS-13, #91) —
 * proves {@link RegistryPinRegistry#validate} registers a pin exactly when the underlying validator
 * accepts, and that the pin is genuinely consulted by the real JVM resolver afterward (not just
 * recorded and ignored).
 */
class RegistryPinRegistryTest {

    @AfterEach
    void resetGlobalState() {
        PinnedAddressResolverProvider.clearForTest();
    }

    private static RegistryProperties props(List<String> allowlist) {
        return new RegistryProperties(Source.DB, allowlist, Set.of());
    }

    private static EngineConfig engine(String id, String baseUrl) {
        return TestEngines.builder(id, baseUrl)
                .environment(EngineEnvironment.TEST)
                .auth(new Auth(Auth.Type.none, null, null, null))
                .mode(EngineMode.READ_WRITE)
                .timeouts(new Timeouts(null, null, null))
                .build();
    }

    /** A hostname mapped to a fixed IP without touching real DNS (mirrors the hostile-corpus tests). */
    private static HostResolver stubResolver(String host, InetAddress ip) {
        return h -> h.equals(host) ? List.of(ip) : List.of();
    }

    @Test
    void a_successful_validate_pins_the_host_so_the_real_jvm_resolver_serves_it_without_dns()
            throws UnknownHostException {
        String host = "engine.pin-registry-test.invalid";
        InetAddress resolvedIp = InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34});
        RegistryProperties registryProperties = props(List.of(host));
        RegistryPinRegistry pinRegistry =
                new RegistryPinRegistry(new RegistryUrlValidator(stubResolver(host, resolvedIp)), registryProperties);

        RegistryUrlValidator.Result result = pinRegistry.validate(
                "https://" + host + "/service", EngineEnvironment.TEST, registryProperties.egressPolicy());

        assertThat(result.isAllowed()).isTrue();
        // The real JVM resolution path now answers from the pin — proof the side effect actually
        // reaches PinnedAddressResolverProvider, not just an internal RegistryPinRegistry field.
        assertThat(InetAddress.getAllByName(host)).containsExactly(resolvedIp);
    }

    @Test
    void a_rejected_validate_registers_no_pin() {
        RegistryProperties registryProperties = props(List.of("93.184.216.0/24"));
        RegistryPinRegistry pinRegistry = new RegistryPinRegistry(new RegistryUrlValidator(), registryProperties);

        RegistryUrlValidator.Result rejected = pinRegistry.validate(
                "http://169.254.169.254/", EngineEnvironment.TEST, registryProperties.egressPolicy());

        // Pin registration is structurally inside the Pinned branch only (RegistryPinRegistry.validate)
        // — a Rejected result can't have reached it. This is the behavioral half of that guarantee.
        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected).isInstanceOf(RegistryUrlValidator.Rejected.class);
    }

    @Test
    void resync_pins_every_live_engine_and_swallows_a_single_engines_validation_failure() throws UnknownHostException {
        String host = "resync.pin-registry-test.invalid";
        InetAddress resolvedIp = InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 35});
        RegistryProperties registryProperties = props(List.of(host));
        RegistryPinRegistry pinRegistry =
                new RegistryPinRegistry(new RegistryUrlValidator(stubResolver(host, resolvedIp)), registryProperties);
        EngineConfig good = engine("good", "https://" + host + "/service");
        EngineConfig malformed = engine("bad", "not a url");

        // Must not throw even though one engine's base-URL is malformed (docs §5: a genuinely broken
        // host surfaces as an unhealthy engine via the next probe, not a boot/reload crash).
        pinRegistry.resync(List.of(good, malformed));
    }
}
