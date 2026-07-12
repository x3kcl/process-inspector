package io.inspector.registry;

import io.inspector.client.FlowableEngineClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The hot-reload seam (docs/REGISTRY-CRUD.md §4). A committed registry write publishes a
 * {@link RegistryChangedEvent}; this listener runs it {@code AFTER_COMMIT} — strictly post-commit,
 * so the in-memory map + client caches can never run ahead of a rolled-back row + audit. In one
 * step it: (1) refreshes {@link EngineRegistry} from the store (the live rows), (2) evicts the
 * affected {@link FlowableEngineClient} caches + R4j named instances so the next call rebuilds
 * against the new config, (3) re-pins every live engine's host (R-OPS-13, #91 — {@link
 * RegistryPinRegistry}) so the DNS-pin the connect path relies on never goes stale, and (4)
 * triggers an immediate read-only re-probe so the health strip reflects the change without
 * waiting for the 30s cycle.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} IS the Spring abstraction over a
 * {@code TransactionSynchronization.afterCommit} hook — a rollback (row or audit) means the event
 * never fires and nothing reloads.
 */
@Component
public class RegistryReloadListener {

    private static final Logger log = LoggerFactory.getLogger(RegistryReloadListener.class);

    private final EngineRegistry registry;
    private final EngineRegistryStore store;
    private final FlowableEngineClient flowable;
    private final EngineHealthService health;
    private final RegistryPinRegistry pinRegistry;

    public RegistryReloadListener(
            EngineRegistry registry,
            EngineRegistryStore store,
            FlowableEngineClient flowable,
            EngineHealthService health,
            RegistryPinRegistry pinRegistry) {
        this.registry = registry;
        this.store = store;
        this.flowable = flowable;
        this.health = health;
        this.pinRegistry = pinRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistryChanged(RegistryChangedEvent event) {
        String id = event.engineId();
        try {
            // Order matters for the lazy computeIfAbsent-by-id client cache: reload FIRST (publish the
            // new config), THEN evict (drop the stale client). A call in this µs window uses the old
            // cached client once; the post-evict rebuild then picks up the new config — self-healing.
            // Evicting BEFORE reload would let a call in the pre-reload window RE-cache the OLD config
            // and leave it stale until the next write. reprobe finally rebuilds the client eagerly.
            registry.reload(store.findLive()); // refresh the whole live set (≤20 engines, cheap)
            flowable.evict(id); // drop cached RestClients + REMOVE the R4j instances for this id
            pinRegistry.resync(registry.all()); // re-pin every live engine (R-OPS-13, #91)
            health.reprobe(id); // immediate read-only re-probe (also warms the new client)
            log.info("Registry hot-reloaded after change to engine '{}'.", id);
        } catch (RuntimeException e) {
            // The committed ROW is the source of truth; the in-memory map is a cache. Never rethrow —
            // a reload hiccup must not fail an already-committed write. NOTE: the 30s health cycle
            // refreshes HEALTH, not the map, so a failed reload leaves the map at its prior state
            // until the NEXT registry write (or a restart) reloads it — logged loudly here.
            log.warn(
                    "Registry reload after change to engine '{}' failed; map stays until next write: {}",
                    id,
                    e.toString(),
                    e);
        }
    }
}
