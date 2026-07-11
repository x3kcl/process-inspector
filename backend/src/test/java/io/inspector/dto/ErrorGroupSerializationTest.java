package io.inspector.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 — the R-BAU-01 wire-shape regression pinned by the W3-2 external review: the
 * generated frontend contract types {@code acknowledgement?:} (OPTIONAL — absent, not
 * null), so an unacknowledged group must serialize WITHOUT the key. A present-but-null
 * key would slip past every {@code !== undefined} check in the SPA and crash the landing
 * on {@code null.resurfaced}. Same rule protects the nullable {@code exceptionClass}.
 */
class ErrorGroupSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unacknowledgedGroupOmitsTheAcknowledgementKeyEntirely() throws Exception {
        ErrorGroup group =
                new ErrorGroup("hash-1", 1, null, "boom #", "boom 42", 10, 10, 0, Map.of("e1", Map.of("k:v1", 10L)));
        String json = mapper.writeValueAsString(group);
        assertThat(json).doesNotContain("acknowledgement");
        assertThat(json).doesNotContain("exceptionClass"); // null → omitted, not null-valued
    }

    @Test
    void decoratedGroupCarriesTheOverlay() throws Exception {
        ErrorGroupAcknowledgement info = new ErrorGroupAcknowledgement(
                "op1", "2026-07-10T09:00:00Z", "known outage window", null, null, 10, false, null, 0);
        ErrorGroup group = new ErrorGroup(
                        "hash-1", 1, "com.acme.Boom", "boom #", "boom 42", 10, 10, 0, Map.of("e1", Map.of("k:v1", 10L)))
                .withAcknowledgement(info);
        String json = mapper.writeValueAsString(group);
        assertThat(json).contains("\"acknowledgement\"");
        assertThat(json).contains("\"acknowledgedBy\":\"op1\"");
    }
}
