package io.inspector.snapshot;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StartByKeyRepository extends JpaRepository<StartByKey, Long> {

    @Query("""
            select s.definitionKey, sum(s.count)
            from StartByKey s
            where s.engineId = :engineId and s.sampledAt >= :since
            group by s.definitionKey
            order by sum(s.count) desc
            """)
    List<Object[]> totalsSince(String engineId, Instant since);

    @Query("""
            select max(case when s.catalogCapped = true then 1 else 0 end)
            from StartByKey s
            where s.engineId = :engineId and s.sampledAt >= :since
            """)
    Integer cappedSince(String engineId, Instant since);
}
