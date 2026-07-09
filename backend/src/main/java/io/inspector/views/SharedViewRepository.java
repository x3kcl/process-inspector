package io.inspector.views;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The team-published shared views store (SHARED-VIEWS.md, R-SEM-24). Unlike {@link
 * SavedViewRepository} these rows are NOT owner-keyed — a shared view is visible to any caller whose
 * grants OVERLAP its scope (resolved in the service via {@code ScopeGrant.overlaps()}, not here). The
 * table is small (team canon in a ≤25-user org), so the read path lists all and filters in the BFF
 * rather than pushing the wildcard-overlap into SQL; it is deliberately NOT cached (a freshly
 * published/unpublished view must appear/vanish immediately across replicas).
 */
public interface SharedViewRepository extends JpaRepository<SharedView, Long> {

    /** All team canon, newest-first — the service applies the caller's overlap filter. */
    List<SharedView> findAllByOrderByCreatedAtDesc();

    /** Publish is create-only: this probes the {@code UNIQUE(name, scope)} identity before insert. */
    Optional<SharedView> findByNameAndScopeEngineIdAndScopeTenantId(
            String name, String scopeEngineId, String scopeTenantId);
}
