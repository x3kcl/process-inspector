package io.inspector.views;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecentSearchRepository extends JpaRepository<RecentSearch, Long> {

    List<RecentSearch> findByOwnerOrderByAtDesc(String owner);

    Optional<RecentSearch> findByOwnerAndSearch(String owner, String search);
}
