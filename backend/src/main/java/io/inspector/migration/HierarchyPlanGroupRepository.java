package io.inspector.migration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HierarchyPlanGroupRepository extends JpaRepository<HierarchyPlanGroup, HierarchyPlanGroup.Key> {

    List<HierarchyPlanGroup> findByPlanId(UUID planId);
}
