package io.inspector.security.mapping;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for the fleet grant store ({@code group_fleet_grant}, V13). */
public interface GroupFleetGrantRepository extends JpaRepository<GroupFleetGrantEntity, Long> {

    Optional<GroupFleetGrantEntity> findByGroupNameAndGrantKind(String groupName, String grantKind);
}
