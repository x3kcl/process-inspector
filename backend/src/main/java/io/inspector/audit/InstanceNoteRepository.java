package io.inspector.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceNoteRepository extends JpaRepository<InstanceNote, Long> {

    List<InstanceNote> findByEngineIdAndInstanceIdOrderByTsDesc(String engineId, String instanceId);
}
