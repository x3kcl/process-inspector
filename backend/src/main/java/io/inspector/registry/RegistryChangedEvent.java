package io.inspector.registry;

/**
 * Published when a registry row is written (add/edit/enable/disable/remove/purge) — the trigger for
 * the hot-reload seam (docs/REGISTRY-CRUD.md §4). Consumed by {@link RegistryReloadListener} with
 * {@code @TransactionalEventListener(AFTER_COMMIT)}, so the in-memory refresh + client eviction +
 * re-probe run STRICTLY after the row + audit have committed — never ahead of a rolled-back write.
 *
 * @param engineId the affected engine id (the evicted client cache + R4j instances are keyed on it)
 */
public record RegistryChangedEvent(String engineId) {}
