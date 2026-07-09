package io.inspector.security.mapping;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for the ladder grant store ({@code group_scope_grant}, V13). */
public interface GroupScopeGrantRepository extends JpaRepository<GroupScopeGrantEntity, Long> {

    Optional<GroupScopeGrantEntity> findByGroupNameAndRoleAndEngineIdAndTenantId(
            String groupName, String role, String engineId, String tenantId);
}
