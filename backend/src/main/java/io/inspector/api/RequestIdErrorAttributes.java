package io.inspector.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * The container {@code /error} path — everything that never reached an {@code @ExceptionHandler}:
 * the security 401/403 ({@code sendError} from the entry-point/access-denied legs, which run in
 * the filter chain before any controller advice can see them) and the no-handler/typo 404. One
 * error contract (issue #87 — F4): this used to render Spring's bare
 * {@code {timestamp,status,error,path}} shape, a THIRD body shape alongside {@code ProblemDetail}
 * and the (now-removed) ad-hoc controller maps. It now renders the SAME
 * {@code type/title/status/detail/instance/code/requestId} shape {@link ActionExceptionHandler}
 * and {@link ProblemDetailRequestIdAdvice} produce, so the SPA's parser never branches on which
 * path an error took.
 *
 * <p>{@code detail} is deliberately the HTTP status's stock reason phrase, NEVER the underlying
 * exception message — unlike {@link ActionExceptionHandler}'s handlers (which only ever see
 * exceptions whose message is developer-authored, client-facing copy), anything reaching THIS
 * path is, by construction, unexpected/unhandled — surfacing its raw message would risk leaking
 * internals (R-AUD-04 stays satisfied by the {@code requestId}, which is quotable to support
 * without needing the server to say more).
 *
 * <p>Read from the request attribute, not MDC: {@code sendError} unwinds the filter chain (MDC
 * cleared) before the container re-dispatches to {@code /error}; the attribute survives.
 */
@Component
public class RequestIdErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> raw = super.getErrorAttributes(webRequest, options);
        int statusCode = raw.get("status") instanceof Number n ? n.intValue() : 500;
        HttpStatus status = HttpStatus.resolve(statusCode);
        String reasonPhrase = status != null ? status.getReasonPhrase() : String.valueOf(statusCode);

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", URI.create("about:blank").toString());
        problem.put("title", reasonPhrase);
        problem.put("status", statusCode);
        problem.put("detail", reasonPhrase);
        Object path = raw.get("path");
        if (path != null) {
            problem.put("instance", path.toString());
        }
        problem.put("code", status != null ? ProblemCodes.fromStatus(status) : String.valueOf(statusCode));

        Object id = webRequest.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (id != null) {
            problem.put("requestId", id);
        }
        return problem;
    }
}
