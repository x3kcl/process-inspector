package io.inspector.registry;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-memory view of the registered engines plus their live health state. Health is written by
 * {@link EngineHealthService} and read by the API layer.
 *
 * <p>v2 Registry CRUD (S3 reload seam, docs/REGISTRY-CRUD.md §4): the engine map is now
 * RELOADABLE. It holds ALL live rows (enabled AND disabled — so a disabled engine still resolves
 * by id for names/history), and the {@code enabled} filter moved from the constructor to
 * {@link #all()} / the fan-out. {@link #reload} atomically swaps the map (a fresh immutable
 * snapshot published through a {@code volatile} field) at boot under {@code source: db} and after
 * every committed registry write; every other consumer already re-reads {@link #all()} /
 * {@link #require} live per call, so the whole system tracks a reload for free. Under
 * {@code source: config} the constructor map stands and reload is never called (restart semantics).
 */
@Component
public class EngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(EngineRegistry.class);

    // volatile: reload() publishes a brand-new immutable map; readers see the swap atomically and
    // never observe a half-built map (the map is never mutated in place after publication).
    private volatile Map<String, EngineConfig> engines;
    private final Map<String, EngineHealth> health = new ConcurrentHashMap<>();

    public EngineRegistry(InspectorProperties props) {
        // Hold ALL configured engines now (enabled filter moved to all()); the DB reload replaces
        // this bootstrap map at boot under source=db.
        this.engines = index(props.engines());
        engines.keySet().forEach(id -> health.put(id, EngineHealth.unknown()));
        warnForwardUser(engines.values());
    }

    /**
     * Replace the live-engine set (S3 reload seam). Health is preserved for retained ids, seeded
     * {@code unknown} for new ones, and dropped for engines no longer present (disabled→removed).
     * Called strictly post-commit by the reload listener so in-memory state never runs ahead of a
     * rolled-back row.
     */
    public void reload(Collection<EngineConfig> live) {
        Map<String, EngineConfig> next = index(live);
        next.keySet().forEach(id -> health.putIfAbsent(id, EngineHealth.unknown()));
        health.keySet().retainAll(next.keySet());
        this.engines = next; // volatile publish
        warnForwardUser(next.values());
    }

    /**
     * Config-load guardrail for the X-Forwarded-User send-side (M4-CLOSEOUT §2 / D2). Forwarding
     * employee identity (PII, outside the §4 payload modes) is trustworthy ONLY over an
     * authenticated / network-isolated BFF→engine channel (D2c) — a property the registry does not
     * model, so we hard-WARN rather than silently forward. We deliberately do not auto-refuse by
     * {@code environment}: "outside the ARCH §6 trust fence" is not a modeled attribute (the
     * classification only distinguishes dev/test/prod), and ARCH §6 explicitly recommends the flag
     * for the embedded-engine (flap) case, which may be prod. Off-by-default already makes enabling
     * it a deliberate act; this WARN keeps that act visible at every load/reload.
     */
    private static void warnForwardUser(Collection<EngineConfig> engines) {
        engines.stream()
                .filter(EngineConfig::forwardUser)
                .forEach(e -> log.warn(
                        "engine '{}' ({}) has forward-user ON: X-Forwarded-User carries employee identity (PII) — "
                                + "ensure a trusted, isolated BFF→engine channel and that the engine strips inbound "
                                + "X-Forwarded-User (M4-CLOSEOUT §2 / ARCH §6).",
                        e.id(),
                        e.environment()));
    }

    // LinkedHashMap: the health strip and every fan-out follow registry (YAML / DB) order.
    private static Map<String, EngineConfig> index(Collection<EngineConfig> engines) {
        return engines.stream()
                .collect(Collectors.toMap(EngineConfig::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /** The enabled engines, in registry order — the fan-out / operable-target surface. */
    public List<EngineConfig> all() {
        return engines.values().stream().filter(EngineConfig::enabled).toList();
    }

    /**
     * ALL live engines (enabled AND disabled/draft/probe_failed), in registry order — the
     * DISPLAY surface (usability W1#4, theme T6): the dashboard renders a non-active engine
     * greyed-with-reason rather than silently omitting it (R-SEM-17/R-GOV-04 doctrine).
     * Never an operable-target source — fan-out and mutation resolution stay on
     * {@link #all()} / {@link #require}.
     */
    public List<EngineConfig> allForDisplay() {
        return List.copyOf(engines.values());
    }

    /**
     * The engine an operation targets — ENABLED only, exactly as before S3 (a disabled engine is not
     * an operable target). Unknown or disabled ⇒ 404. (Distinct from {@link #resolve}, which returns
     * disabled rows for id→name lookups.)
     */
    public EngineConfig require(String engineId) {
        EngineConfig cfg = engines.get(engineId);
        if (cfg == null || !cfg.enabled()) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown engine: " + engineId);
        }
        return cfg;
    }

    /**
     * Look up ANY live engine by id — enabled OR disabled — for id→name/history resolution that must
     * survive a disable (docs/REGISTRY-CRUD.md §4; the admin surface + audit/notes display use this).
     * Empty for an unknown id or a tombstoned/removed engine (both leave the map). Never an operable
     * target on its own — callers that mutate go through {@link #require}.
     */
    public Optional<EngineConfig> resolve(String engineId) {
        return Optional.ofNullable(engines.get(engineId));
    }

    /**
     * {@link #resolve} with the read-surface's standard "unknown engine" 404 (#248/#252): a
     * registered-but-disabled engine still resolves — only a genuinely unknown or
     * tombstoned/removed id 404s. Read-only services that need this survive-a-disable seam
     * call this directly instead of duplicating the resolve-or-404 snippet per service.
     */
    public EngineConfig resolveOrNotFound(String engineId) {
        return resolve(engineId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Unknown engine: " + engineId));
    }

    public EngineHealth healthOf(String engineId) {
        return health.getOrDefault(engineId, EngineHealth.unknown());
    }

    public void updateHealth(String engineId, EngineHealth h) {
        health.put(engineId, h);
    }
}
