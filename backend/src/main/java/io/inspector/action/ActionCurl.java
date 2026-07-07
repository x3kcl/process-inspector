package io.inspector.action;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The "Show as cURL" honesty renderer (v1.x #6). Like {@link io.inspector.api.CriteriaEcho},
 * the copy-as-cURL is SERVER-computed and rendered verbatim by the UI — never re-assembled
 * client-side — so what the operator sees is exactly the request the BFF will dispatch.
 *
 * <p>Two deliberate safety choices flow from the iron rules:
 *
 * <ul>
 *   <li>The command targets the <b>BFF endpoint</b> (the whitelisted {@code POST …/actions/…}
 *       route), never the engine's own REST API — the browser never has an engine path, and
 *       the engine's per-engine credentials must not travel to the client (R-GOV, no generic
 *       proxy).
 *   <li>The {@code Authorization} header is a <b>placeholder</b> ({@code <your-credentials>}),
 *       not a live token — secrets never appear in a response body (corrective-actions §? /
 *       env-ref rule). The operator fills their own credential in.
 * </ul>
 *
 * <p>The body is the {@link ActionRequest} re-serialized with nulls dropped, so the copied
 * command reproduces exactly the payload the modal is about to POST. Pure request→text.
 */
public final class ActionCurl {

    private static final ObjectMapper CURL_BODY_MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ActionCurl() {}

    /**
     * @param url the externally visible BFF action URL (the controller derives it from the
     *     incoming request so it survives reverse proxies)
     * @param request the exact body the execute call will send
     */
    public static String render(String url, ActionRequest request) {
        String body;
        try {
            body = CURL_BODY_MAPPER.writeValueAsString(request != null ? request : ActionRequest.empty());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ActionRequest is not serializable", e);
        }
        return "curl -X POST '" + shellSingleQuote(url) + "'"
                + " -H 'Content-Type: application/json'"
                + " -H 'Authorization: Basic <your-credentials>'"
                + " -d '" + shellSingleQuote(body) + "'";
    }

    /** POSIX single-quote escaping: close, emit an escaped quote, reopen ({@code '\''}). */
    private static String shellSingleQuote(String s) {
        return s.replace("'", "'\\''");
    }
}
