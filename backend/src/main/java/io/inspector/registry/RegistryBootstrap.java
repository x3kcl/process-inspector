package io.inspector.registry;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.RegistryProperties;
import io.inspector.registry.RegistryDrift.DriftReport;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Boot-time registry source resolution (docs/REGISTRY-CRUD.md §4). Runs once, after the context is
 * ready:
 *
 * <ul>
 *   <li>{@code source: config} — config-pinned: nothing is seeded, R-SEM-17 restart semantics hold.
 *   <li>{@code source: db} + empty table — import {@code inspector.engines} YAML as the one-time
 *       audited seed. Fail-closed: if the audit/DB write throws, the app still boots with an empty
 *       registry (the next boot retries) — an audit failure never produces a silent partial seed
 *       AND never crashes startup.
 *   <li>{@code source: db} + non-empty table — DB wins; if YAML is also present, log the per-engine
 *       drift so an ignored {@code prod.yaml} edit is visible, never silent.
 * </ul>
 *
 * <p>S2 does NOT yet point {@code EngineRegistry} at the store — that (and live reload) is S3. Here
 * the seed simply makes the DB the future source of truth.
 */
@Component
public class RegistryBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RegistryBootstrap.class);

    private final RegistryProperties registryProperties;
    private final InspectorProperties inspectorProperties;
    private final EngineRegistryStore store;
    private final EngineRegistry registry;
    private final RegistryPinRegistry pinRegistry;

    public RegistryBootstrap(
            RegistryProperties registryProperties,
            InspectorProperties inspectorProperties,
            EngineRegistryStore store,
            EngineRegistry registry,
            RegistryPinRegistry pinRegistry) {
        this.registryProperties = registryProperties;
        this.inspectorProperties = inspectorProperties;
        this.store = store;
        this.registry = registry;
        this.pinRegistry = pinRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (registryProperties.isConfigPinned()) {
            log.info("Engine registry is config-pinned (inspector.registry.source=config): "
                    + "CRUD disabled, YAML is authoritative, changes require a restart.");
            // Config-pinned engines are still dialled by FlowableEngineClient — pin them too (R-OPS-13,
            // #91), or this deployment mode would never close the DNS-rebinding TOCTOU.
            pinRegistry.resync(registry.all());
            return;
        }

        List<EngineConfig> yamlEngines = inspectorProperties.engines();
        if (store.isEmpty()) {
            if (yamlEngines.isEmpty()) {
                log.info("Engine registry is empty and inspector.engines has no entries — nothing to seed.");
            } else {
                seedEmptyRegistry(yamlEngines);
            }
        } else if (!yamlEngines.isEmpty()) {
            // Non-empty: DB is authoritative. Surface (never swallow) any divergence from YAML.
            DriftReport drift = store.driftReport(yamlEngines);
            if (drift.isEmpty()) {
                log.info("Engine registry loaded from DB; inspector.engines YAML matches (no drift).");
            } else {
                log.warn(drift.summary());
            }
        }

        // Point EngineRegistry at the DB (S3): under source=db the store is the source of truth, so
        // the in-memory map now reflects the live rows (replacing the bootstrap map built from YAML).
        registry.reload(store.findLive());
        pinRegistry.resync(registry.all()); // pin every live engine at boot (R-OPS-13, #91)
    }

    private void seedEmptyRegistry(List<EngineConfig> yamlEngines) {
        try {
            int seeded = store.seedFromConfig(yamlEngines);
            log.info("Seeded {} engine(s) from inspector.engines YAML into the empty registry.", seeded);
        } catch (RuntimeException e) {
            // Fail-closed: nothing this instance did persisted (the @Transactional seed is
            // all-or-nothing). Report the cause honestly instead of always claiming "empty": a
            // concurrent instance may have won the seed race (its rows committed, so OUR insert hit
            // the unique-id PK and rolled us back) — the registry IS seeded, nothing to retry.
            // Otherwise the audit/DB was unavailable — boot empty, retry next start.
            if (!store.isEmpty()) {
                log.info("Engine registry was seeded by a concurrent instance; continuing.");
            } else {
                log.warn(
                        "Could not seed the engine registry from YAML (audit/DB unavailable?) — "
                                + "booting with an empty registry, will retry on next start: {}",
                        e.toString());
            }
        }
    }
}
