package io.inspector.views;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface SavedViewRepository extends JpaRepository<SavedView, Long> {

    List<SavedView> findByOwnerOrderByCreatedAtDesc(String owner);

    Optional<SavedView> findByOwnerAndName(String owner, String name);

    /** Ownership-scoped delete — a user can only remove their OWN view (returns rows deleted). */
    @Transactional
    long deleteByIdAndOwner(Long id, String owner);
}
