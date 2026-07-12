package io.inspector.registry;

import io.inspector.config.InspectorProperties.EngineEnvironment;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * JEP 418 DNS-resolution SPI hook that closes the resolve→connect DNS-rebinding TOCTOU for
 * registry-registered engine hosts (docs/REGISTRY-CRUD.md §5, R-OPS-13, #91): {@link
 * RegistryUrlValidator#validate} resolves-then-pins a host at add/edit/probe/reload time (via
 * {@link RegistryPinRegistry}); every ACTUAL socket connect to that host — the health loop, every
 * operation dial, all funnelled through {@link io.inspector.client.FlowableEngineClient} — goes
 * through the JDK's normal DNS lookup path, which this provider intercepts: a pinned host is
 * answered with its PINNED literal IP (never re-resolved; re-checked against the CURRENT denylist
 * via {@link RegistryUrlValidator#isPinAllowed}) instead of asking the OS resolver again. A
 * hostname we never pinned (Postgres, the OIDC issuer, every other outbound call the JVM makes)
 * falls straight through to the platform resolver — this provider is a no-op for it.
 *
 * <p>Registered via {@code META-INF/services/java.net.spi.InetAddressResolverProvider} — the JDK
 * {@code ServiceLoader} instantiates this once per JVM, so its state is necessarily static;
 * {@link RegistryPinRegistry} (a Spring bean) is the only writer, bridged through the static
 * {@link #register} / {@link #setChecker} entry points.
 */
public final class PinnedAddressResolverProvider extends InetAddressResolverProvider {

    private static final Map<String, PinEntry> PINS = new ConcurrentHashMap<>();

    // Permissive default until RegistryPinRegistry wires the real check at Spring startup — a host
    // is only ever present in PINS once that same bean registers it, so nothing is pinned (hence
    // nothing is checked) before the bean exists.
    private static volatile PinChecker checker = (ip, environment) -> true;

    private record PinEntry(InetAddress ip, EngineEnvironment environment) {}

    @FunctionalInterface
    interface PinChecker {
        boolean isAllowed(InetAddress ip, EngineEnvironment environment);
    }

    static void register(String host, InetAddress ip, EngineEnvironment environment) {
        PINS.put(host.toLowerCase(Locale.ROOT), new PinEntry(ip, environment));
    }

    static void setChecker(PinChecker c) {
        checker = c;
    }

    /** Test-only reset — the map is a static JVM-wide singleton (see class doc). */
    static void clearForTest() {
        PINS.clear();
        checker = (ip, environment) -> true;
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver system = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                PinEntry pin = PINS.get(host.toLowerCase(Locale.ROOT));
                if (pin == null) {
                    return system.lookupByName(host, lookupPolicy);
                }
                if (!checker.isAllowed(pin.ip(), pin.environment())) {
                    throw new UnknownHostException(
                            host + ": pinned address rejected by the connect-time recheck (R-OPS-13)");
                }
                return Stream.of(pin.ip());
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return system.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "inspector-registry-pin";
    }
}
