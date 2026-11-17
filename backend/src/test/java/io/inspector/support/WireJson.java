package io.inspector.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Canonical text of a JSON request body for the query characterizations (#720): object keys
 * sorted, no whitespace, values untouched — a number stays a number, a string a string, an
 * explicit {@code null} stays visible and an absent key stays absent. The search bodies on
 * {@code main} were {@code HashMap}s, so their key ORDER was a hash accident; everything else
 * about the body is pinned.
 */
public final class WireJson {

    private static final ObjectMapper SORTED = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private WireJson() {}

    /** Canonical form of JSON text as it went over the wire. */
    public static String canonical(String json) {
        try {
            return SORTED.writeValueAsString(SORTED.readValue(json, Object.class));
        } catch (JsonProcessingException e) {
            throw new AssertionError("not JSON: " + json, e);
        }
    }

    /** Canonical form of a body object (a map or a request record) as Jackson would send it. */
    public static String canonicalOf(Object body) {
        try {
            return canonical(new ObjectMapper().writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }
}
