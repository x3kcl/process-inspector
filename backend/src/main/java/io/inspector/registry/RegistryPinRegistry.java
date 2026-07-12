package io.inspector.registry;

import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.RegistryProperties;
import java.net.InetAddress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Spring-side half of connect-time IP pinning (docs/REGISTRY-CRUD.md §5, R-OPS-13, #91) — bridges
 * every legitimate {@link RegistryUrlValidator#validate} call to the static
 * {@link PinnedAddressResolverProvider}, which is the actual JVM-wide DNS interception point (a JDK
 * {@code ServiceLoader} SPI provider can't be a Spring bean).
 *
 * <p>A host is (re-)pinned on every add/edit/probe ({@link #validate}, a drop-in wrapper around
 * {@link RegistryUrlValidator#validate} used by {@link EngineRegistryStore} and
 * {@link io.inspector.api.AdminEnginesController}) AND on boot + every registry reload
 * ({@link #resync}, wired from {@link RegistryBootstrap} and {@link RegistryReloadListener}). Between
 * those admin-triggered moments, ordinary traffic (the health loop, every operation dial) never
 * re-resolves — {@link PinnedAddressResolverProvider} answers straight from the pin, re-checked
 * against the CURRENT denylist via {@link RegistryUrlValidator#isPinAllowed} on every connect.
 */
@Component
public class RegistryPinRegistry {

    private static final Logger log = LoggerFactory.getLogger(RegistryPinRegistry.class);

    private final RegistryUrlValidator urlValidator;
    private final RegistryProperties registryProperties;

    public RegistryPinRegistry(RegistryUrlValidator urlValidator, RegistryProperties registryProperties) {
        this.urlValidator = urlValidator;
        this.registryProperties = registryProperties;
        PinnedAddressResolverProvider.setChecker(this::isPinAllowed);
    }

    /**
     * Validate exactly like {@link RegistryUrlValidator#validate}, plus the pin side-effect: a
     * {@code Pinned} result registers {@code host → pinnedIp} for connect-time reuse. Callers keep
     * their existing {@code Result}-handling logic unchanged (a drop-in wrapper).
     */
    public RegistryUrlValidator.Result validate(
            String baseUrl, EngineEnvironment environment, RegistryEgressPolicy policy) {
        RegistryUrlValidator.Result result = urlValidator.validate(baseUrl, environment, policy);
        if (result instanceof RegistryUrlValidator.Pinned pinned) {
            PinnedAddressResolverProvider.register(pinned.canonicalHost(), pinned.pinnedIp(), environment);
        }
        return result;
    }

    /**
     * Re-pin every live engine — boot ({@link RegistryBootstrap}) and after every committed registry
     * change ({@link RegistryReloadListener}). Best-effort: a host that fails to (re-)validate just
     * keeps its LAST pin (it is never re-resolved on an ordinary dial regardless) — a genuinely broken
     * host surfaces as an unhealthy engine via the next probe, not a crash here.
     */
    public void resync(List<EngineConfig> liveEngines) {
        for (EngineConfig engine : liveEngines) {
            try {
                validate(engine.baseUrl(), engine.environment(), registryProperties.egressPolicy());
            } catch (RuntimeException e) {
                log.warn("Could not (re-)pin engine '{}' ({}): {}", engine.id(), engine.baseUrl(), e.toString());
            }
        }
    }

    private boolean isPinAllowed(InetAddress ip, EngineEnvironment environment) {
        return urlValidator.isPinAllowed(ip, environment, registryProperties.egressPolicy());
    }
}
