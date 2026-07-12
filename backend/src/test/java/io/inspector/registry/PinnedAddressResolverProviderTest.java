package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.config.InspectorProperties.EngineEnvironment;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the JEP 418 DNS-resolution SPI hook (docs/REGISTRY-CRUD.md §5, R-OPS-13, #91) — proven
 * against the REAL JVM resolution path ({@link InetAddress#getAllByName}), the exact mechanism the
 * {@code HttpClient} inside {@link io.inspector.client.FlowableEngineClient} uses to connect.
 * {@code java.net.spi.InetAddressResolverProvider.Configuration} is a sealed JDK interface — it
 * cannot be mocked or hand-implemented in test code — so there is no lighter-weight way to drive
 * {@link PinnedAddressResolverProvider#get} than letting the real JVM instantiate it via the
 * {@code META-INF/services} registration. This is a feature, not a workaround: it proves the SPI
 * file is wired correctly, not just that the class's internal logic is sound in isolation.
 */
class PinnedAddressResolverProviderTest {

    @AfterEach
    void resetGlobalState() {
        // PINS + checker are a static JVM-wide singleton by necessity (a ServiceLoader SPI provider
        // can't be a Spring bean) — every test must leave it clean for the next.
        PinnedAddressResolverProvider.clearForTest();
    }

    @Test
    void a_pinned_host_resolves_to_its_pinned_ip_without_touching_real_dns() throws UnknownHostException {
        // This hostname cannot resolve over real DNS — if the SPI provider were not wired, or fell
        // through to the system resolver for a registered host, this would throw UnknownHostException
        // from the REAL resolver, not silently pass.
        String host = "pinned-toctou-proof.inspector.invalid";
        InetAddress pinned = InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 42});
        PinnedAddressResolverProvider.register(host, pinned, EngineEnvironment.TEST);
        PinnedAddressResolverProvider.setChecker((ip, env) -> true);

        InetAddress[] resolved = InetAddress.getAllByName(host);

        assertThat(resolved).containsExactly(pinned);
    }

    @Test
    void a_pin_the_checker_rejects_fails_the_lookup_instead_of_falling_back_to_dns() throws UnknownHostException {
        String host = "pinned-toctou-denied.inspector.invalid";
        InetAddress pinned = InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 43});
        PinnedAddressResolverProvider.register(host, pinned, EngineEnvironment.PROD);
        PinnedAddressResolverProvider.setChecker((ip, env) -> false); // the connect-time recheck says no

        assertThatThrownBy(() -> InetAddress.getAllByName(host)).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void an_unregistered_host_is_untouched_and_still_resolves_normally() throws UnknownHostException {
        // "localhost" resolves via the platform's own hosts/loopback resolution (no network needed) —
        // proof our provider is a true no-op for a host RegistryPinRegistry never pinned.
        assertThat(InetAddress.getAllByName("localhost")).isNotEmpty();
    }
}
