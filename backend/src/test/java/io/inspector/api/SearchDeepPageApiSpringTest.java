package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.aggregate.PagingCursor;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.support.NoDbTestSupport;
import java.util.List;
import java.util.Map;
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
 * Rung 3: the {@code POST /api/search} deep-paging surface (S3) — the {@code cursor} field
 * deserializes, the new {@code nextCursor}/{@code depthCapped}/{@code pagingCoherence} response
 * fields serialize, and a crafted/garbage/incoherent cursor is a 400 at the web layer (not a 500),
 * for an authenticated reader (the deferred S2 web-layer RBAC check). No engines are configured
 * (NoDbTestSupport), so the paths exercised here never fan out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class SearchDeepPageApiSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void authenticate() {
        rest = rest.withBasicAuth("viewer", "dev");
    }

    @Test
    void anOrdinarySearchCarriesTheDeepPageMarkersAsTheirNeutralDefaults() throws Exception {
        ResponseEntity<String> res = rest.postForEntity(
                "/api/search", Map.of("statuses", List.of("ACTIVE"), "sortBy", "startTime"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.get("depthCapped").asBoolean()).isFalse();
        assertThat(body.get("nextCursor").isNull()).isTrue();
        assertThat(body.get("pagingCoherence").isNull()).isTrue();
    }

    @Test
    void aGarbageCursorIsA400NotA500() throws Exception {
        ResponseEntity<String> res = rest.postForEntity(
                "/api/search",
                Map.of("statuses", List.of("ACTIVE"), "sortBy", "startTime", "cursor", "!!!not-a-cursor!!!"),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aCraftedOverCapOffsetCursorIsRefusedWithA400ThroughTheController() throws Exception {
        // Build the exact request the server will receive, so the cursor's filterHash binds; only the
        // per-engine offset bound-check can then reject it (the DoS ceiling, reachable over the wire).
        SearchRequest req = new SearchRequest(
                List.of("engine-a"),
                List.of(InstanceStatus.ACTIVE),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "startTime",
                50);
        PagingCursor forged = new PagingCursor(
                PagingCursor.VERSION,
                System.currentTimeMillis(),
                PagingCursor.filterHash(req),
                "startTime",
                "desc",
                Map.of("engine-a", 9_999_999),
                null,
                List.of());

        ResponseEntity<String> res = rest.postForEntity(
                "/api/search",
                Map.of(
                        "engineIds", List.of("engine-a"),
                        "statuses", List.of("ACTIVE"),
                        "sortBy", "startTime",
                        "pageSize", 50,
                        "cursor", forged.encode()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(res.getBody()).get("error").asText()).contains("out of range");
    }

    @Test
    void aFailedOnlySearchWithACursorIsRefused() throws Exception {
        ResponseEntity<String> res = rest.postForEntity(
                "/api/search",
                Map.of("statuses", List.of("FAILED"), "sortBy", "startTime", "cursor", someCursor()),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String someCursor() {
        return new PagingCursor(
                        PagingCursor.VERSION,
                        System.currentTimeMillis(),
                        "x",
                        "startTime",
                        "desc",
                        Map.of(),
                        null,
                        List.of())
                .encode();
    }
}
