package io.inspector.migration;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HierarchyPlanRepository extends JpaRepository<HierarchyPlan, UUID> {}
