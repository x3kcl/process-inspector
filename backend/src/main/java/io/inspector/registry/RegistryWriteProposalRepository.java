package io.inspector.registry;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for the registry four-eyes proposal store ({@code registry_write_proposal}, V16). */
public interface RegistryWriteProposalRepository extends JpaRepository<RegistryWriteProposal, Long> {

    List<RegistryWriteProposal> findByStatusOrderByCreatedAtDesc(String status);
}
