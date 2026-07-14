package io.inspector.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProtectedDefinitionRepository extends JpaRepository<ProtectedDefinition, ProtectedDefinition.Key> {}
