package io.inspector.security.mapping;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for the four-eyes proposal store ({@code access_grant_proposal}, V14). */
public interface AccessGrantProposalRepository extends JpaRepository<AccessGrantProposal, Long> {

    List<AccessGrantProposal> findByStatusOrderByCreatedAtDesc(String status);
}
