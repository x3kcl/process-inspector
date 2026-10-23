package io.inspector.migration;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationAnalysisRepository extends JpaRepository<MigrationAnalysis, UUID> {}
