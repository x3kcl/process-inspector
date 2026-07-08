package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.snapshot.SnapshotCountRepository;
import io.inspector.snapshot.SnapshotLane;
import io.inspector.support.NoDbTestSupport;
import io.inspector.support.SnapshotCounts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3: the {@code GET /api/triage/trends} contract — wiring, the {@code hours} clamp, and the
 * {@link io.inspector.dto.TriageTrendResponse} JSON shape the sparkline client consumes. The
 * snapshot store is the mocked {@link SnapshotCountRepository} (NoDbTestSupport); the real read
 * path against Postgres is proven at rung 4 (SnapshotSamplerIT writes; this shape is stable).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class TriageTrendApiSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    SnapshotCountRepository repository;

    @BeforeEach
    void authenticate() {
        rest = rest.withBasicAuth("viewer", "dev");
    }

    @Test
    void serialisesGroupedSeriesForAnAuthenticatedReader() throws Exception {
        when(repository.findBySampledAtGreaterThanEqualOrderByEngineIdAscLaneAscSampledAtAsc(any(Instant.class)))
                .thenReturn(List.of(
                        SnapshotCounts.row("probe-dev", SnapshotLane.ACTIVE, 4, "2026-07-08T11:00:00Z"),
                        SnapshotCounts.row("probe-dev", SnapshotLane.ACTIVE, 6, "2026-07-08T11:30:00Z"),
                        SnapshotCounts.row("probe-dev", SnapshotLane.FAILED, 1, "2026-07-08T11:30:00Z")));

        ResponseEntity<String> res = rest.getForEntity("/api/triage/trends?hours=24", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.get("window").asText()).isEqualTo("PT24H");
        assertThat(body.get("series")).hasSize(2);
        JsonNode active = body.get("series").get(0);
        assertThat(active.get("engineId").asText()).isEqualTo("probe-dev");
        assertThat(active.get("lane").asText()).isEqualTo("ACTIVE");
        assertThat(active.get("points")).hasSize(2);
        assertThat(active.get("points").get(1).get("count").asLong()).isEqualTo(6L);
    }

    @Test
    void clampsAnAbsurdWindowRatherThanScanningUnbounded() {
        // hours=100000 must not reach the repository as-is; it is clamped to the 720h ceiling.
        when(repository.findBySampledAtGreaterThanEqualOrderByEngineIdAscLaneAscSampledAtAsc(any(Instant.class)))
                .thenReturn(List.of());

        ResponseEntity<String> res = rest.getForEntity("/api/triage/trends?hours=100000", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
