package io.inspector.triage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The acknowledgment store's read/write path. Two access patterns only: the render-time
 * join reads the WHOLE table (bounded by the count of distinct acknowledged signatures —
 * a human-curated set, never data-sized) and the ack/unack doors read one signature's
 * slice rows. No caching: the triage engine cache stays engine-data-only by design
 * (R-BAU-01 — ack state joins at render time so an ack/unack is visible on the very next
 * dashboard read, without busting the 20s engine aggregation).
 */
public interface ErrorGroupAckRepository extends JpaRepository<ErrorGroupAck, Long> {

    List<ErrorGroupAck> findBySignatureHashAndAlgoVersion(String signatureHash, int algoVersion);
}
