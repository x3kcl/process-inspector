package io.inspector.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.VariableFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The compiled-criteria echo + copy-as-cURL (SPEC Stage 1, §8): one human-readable line per
 * filter CATEGORY makes the combination rule visible — AND between the lines, OR spelled out
 * within a line ("Status: FAILED or RETRYING"). This replaces a query language, which
 * Flowable's query REST cannot honestly execute (SPEC §11) — the echo teaches the API instead.
 *
 * <p>Pure request→text; no engine or servlet state. The cURL body re-serializes the request
 * with nulls dropped, so the copied command reproduces exactly the search that ran.
 */
public final class CriteriaEcho {

    private static final ObjectMapper CURL_BODY_MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private CriteriaEcho() {}

    /** One line per applied filter category; empty list = unfiltered ("everything") search. */
    public static List<String> echo(SearchRequest req) {
        List<String> lines = new ArrayList<>();
        if (has(req.engineIds())) lines.add("Engines: " + String.join(" or ", req.engineIds()));
        if (has(req.statuses())) {
            lines.add("Status: " + req.statuses().stream().map(Enum::name).collect(Collectors.joining(" or ")));
        }
        if (notBlank(req.processDefinitionKey())) lines.add("Definition: " + req.processDefinitionKey());
        if (req.definitionVersion() != null) lines.add("Definition version: v" + req.definitionVersion());
        if (notBlank(req.businessKey())) lines.add("Business key: exactly '" + req.businessKey() + "'");
        if (notBlank(req.businessKeyLike())) lines.add("Business key: contains '" + req.businessKeyLike() + "'");
        if (notBlank(req.startedAfter())) lines.add("Started after: " + req.startedAfter());
        if (notBlank(req.startedBefore())) lines.add("Started before: " + req.startedBefore());
        if (notBlank(req.failureTimeAfter())) lines.add("Failed after: " + req.failureTimeAfter());
        if (notBlank(req.failureTimeBefore())) lines.add("Failed before: " + req.failureTimeBefore());
        if (has(req.variables())) {
            lines.add("Variables: "
                    + req.variables().stream().map(CriteriaEcho::variableLine).collect(Collectors.joining(" and ")));
        }
        if (notBlank(req.currentActivity())) lines.add("Current activity: contains '" + req.currentActivity() + "'");
        if (notBlank(req.errorText())) lines.add("Error text: contains '" + req.errorText() + "'");
        if (notBlank(req.signatureHash())) lines.add("Error signature: " + abbreviate(req.signatureHash()));
        if ("failureTime".equals(req.sortBy())) lines.add("Sort: failure time (newest first)");
        return lines;
    }

    /**
     * Copy-as-cURL against the BFF search endpoint. {@code url} is the externally visible
     * request URL (the controller derives it from the incoming request, so it survives
     * reverse proxies); the body is single-quoted with POSIX {@code '\''} escaping.
     */
    public static String curl(String url, SearchRequest req) {
        String body;
        try {
            body = CURL_BODY_MAPPER.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SearchRequest is not serializable", e);
        }
        return "curl -X POST '" + shellSingleQuote(url) + "' -H 'Content-Type: application/json' -d '"
                + shellSingleQuote(body) + "'";
    }

    /** A 64-hex signature hash is an opaque key — echo a recognizable stub, not a wall. */
    private static String abbreviate(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "…";
    }

    private static String variableLine(VariableFilter v) {
        String op = v.operation() != null ? v.operation() : "equals";
        return "'" + v.name() + "' " + op + " '" + v.value() + "'";
    }

    private static String shellSingleQuote(String s) {
        return s.replace("'", "'\\''");
    }

    private static boolean has(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
