package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.support.EngineSeed;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Rung 4 (engine-harness): v2 k-way-merge deep paging against a REAL flowable-rest engine — the
 * cursor chain end-to-end on live state. Concrete subclasses pin the version + profile (6.8, 7.1);
 * every instance is seeded organically over REST (never SQL) and each test filters by a unique
 * businessKey so it sees ONLY its own instances on the shared KEEP-up dev stack. Caps are
 * config-LOWERED ({@code max-page-size:2}, {@code deep-paging-max-depth:6}) so correctness proves
 * on a handful of seeded instances, never thousands (TEST-STRATEGY §10).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractKwayPagingIT {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    /** The engine's REST base URL (e.g. {@code http://localhost:8081/flowable-rest/service}). */
    protected abstract String engineUrl();

    /** Registered engine id with the DEFAULT depth cap (correctness scroll never caps). */
    protected abstract String scrollEngineId();

    /** Registered engine id with {@code deep-paging-max-depth:6} (the config-lowered cap under test). */
    protected abstract String cappedEngineId();

    /** Registered engine id that nothing listens on (drop-mid-scroll honesty). */
    protected abstract String downEngineId();

    private RestClient engine;
    private final String scrollKey = "kway-scroll-" + UUID.randomUUID();
    private final String cappedKey = "kway-capped-" + UUID.randomUUID();
    private List<String> scrollIds;
    private List<String> cappedIds;

    @BeforeAll
    void seedOrganically() {
        rest = rest.withBasicAuth("admin", "dev");
        engine = EngineSeed.requireReachable(engineUrl(), "");
        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);
        // Six ACTIVE (user-task-parked) instances to scroll, eight to overrun the depth cap of 6.
        scrollIds = seed(scrollKey, 6);
        cappedIds = seed(cappedKey, 8);
    }

    @AfterAll
    void cleanup() {
        if (scrollIds != null) scrollIds.forEach(id -> EngineSeed.deleteInstanceQuietly(engine, id));
        if (cappedIds != null) cappedIds.forEach(id -> EngineSeed.deleteInstanceQuietly(engine, id));
    }

    private List<String> seed(String businessKey, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(EngineSeed.startInstance(engine, "demoUserTask", businessKey, List.of()));
        }
        return ids;
    }

    @Test
    void deepScrollEmitsEveryInstanceExactlyOnceInStartTimeDescOrder() throws Exception {
        List<String> emitted = new ArrayList<>();
        List<Instant> startTimes = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            JsonNode body = search(page(scrollEngineId(), scrollKey, 2, cursor));
            for (JsonNode row : body.get("rows")) {
                emitted.add(row.get("processInstanceId").asText());
                startTimes.add(Instant.parse(row.get("startTime").asText()));
            }
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asText() : null;
        } while (cursor != null && ++guard < 20);

        assertThat(guard).as("cursor chain terminated (not a runaway loop)").isLessThan(19);
        // Exactly the seeded set — no skip, no duplicate — across the whole chain, newest-first.
        assertThat(emitted).containsExactlyInAnyOrderElementsOf(scrollIds);
        assertThat(emitted).doesNotHaveDuplicates();
        assertThat(startTimes).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void pagingPastTheConfigLoweredDepthCapFlagsDepthCapped() throws Exception {
        boolean sawDepthCapped = false;
        String cursor = null;
        int guard = 0;
        do {
            JsonNode body = search(page(cappedEngineId(), cappedKey, 3, cursor));
            if (body.path("depthCapped").asBoolean(false)) sawDepthCapped = true;
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asText() : null;
        } while (cursor != null && ++guard < 20);

        assertThat(guard).as("cursor chain terminated (not a runaway loop)").isLessThan(19);
        // Eight instances, cap 6 → paging past offset 6 is honestly flagged (the depth-wall seam).
        assertThat(sawDepthCapped).isTrue();
    }

    @Test
    void aCraftedCursorOverThePerEngineCapIsRefusedWithA400() {
        SearchRequest req = new SearchRequest(
                List.of(cappedEngineId()),
                List.of(InstanceStatus.ACTIVE),
                null,
                null,
                cappedKey,
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
                3);
        PagingCursor forged = new PagingCursor(
                PagingCursor.VERSION,
                System.currentTimeMillis(),
                PagingCursor.filterHash(req),
                "startTime",
                "desc",
                Map.of(cappedEngineId(), 9_999),
                null,
                List.of());

        Map<String, Object> body = page(cappedEngineId(), cappedKey, 3, forged.encode());
        ResponseEntity<String> res = rest.postForEntity("/api/search", body, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDroppedEngineMidScrollDegradesHonestlyWhileThePageStillReturns() throws Exception {
        Map<String, Object> req = page(scrollEngineId(), scrollKey, 2, null);
        req.put("engineIds", List.of(scrollEngineId(), downEngineId()));

        JsonNode body = search(req);
        // The unreachable engine is a labeled failure inside a 200; the healthy engine's rows stand.
        assertThat(body.get("perEngine").get(downEngineId()).get("ok").asBoolean())
                .isFalse();
        assertThat(body.get("rows")).isNotEmpty();
        for (JsonNode row : body.get("rows")) {
            assertThat(row.get("engineId").asText()).isEqualTo(scrollEngineId());
        }
    }

    private Map<String, Object> page(String engineId, String businessKey, int pageSize, String cursor) {
        Map<String, Object> req = new HashMap<>();
        req.put("engineIds", List.of(engineId));
        req.put("statuses", List.of("ACTIVE"));
        req.put("businessKey", businessKey);
        req.put("sortBy", "startTime");
        req.put("pageSize", pageSize);
        if (cursor != null) req.put("cursor", cursor);
        return req;
    }

    private JsonNode search(Map<String, Object> request) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/search", request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }
}
