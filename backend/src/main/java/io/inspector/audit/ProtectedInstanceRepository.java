package io.inspector.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProtectedInstanceRepository extends JpaRepository<ProtectedInstance, ProtectedInstance.Key> {}
