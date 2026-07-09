package io.inspector.registry;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA access to the V7 {@code engine_registry} table (docs/REGISTRY-CRUD.md §10). Mirrors the V6
 * saved-view repository shape. Reads are the registry source-of-truth under
 * {@code inspector.registry.source: db}; the store service ({@link EngineRegistryStore}) wraps
 * every write in a transaction and the fail-closed audit rail.
 */
public interface EngineRegistryRepository extends JpaRepository<EngineRegistryRow, String> {

    /** Every non-tombstoned row (draft/probed/active/disabled), YAML order not guaranteed. */
    List<EngineRegistryRow> findByRemovedAtIsNull();

    /** A single live (non-tombstoned) row by its immutable id. */
    Optional<EngineRegistryRow> findByIdAndRemovedAtIsNull(String id);
}
