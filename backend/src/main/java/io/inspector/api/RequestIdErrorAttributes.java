package io.inspector.api;

import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * R-AUD-04 (usability W1#6): the container {@code /error} path — everything that never reached a
 * handler: the security 403 ({@code sendError} from the access-denied leg), the no-handler/typo
 * 404 — renders Spring's bare {@code {timestamp,status,error,path}} shape, which the baseline
 * run flagged as quotable-id-less (RUN-REPORT theme 5). This customizer adds the request's id as
 * an additive {@code requestId} field; the shape otherwise stays exactly Spring's (the SPA's
 * bare-shape detection in {@code problem.ts} keys on {@code status}+{@code error}, both kept).
 *
 * <p>Read from the request attribute, not MDC: {@code sendError} unwinds the filter chain (MDC
 * cleared) before the container re-dispatches to {@code /error}; the attribute survives.
 */
@Component
public class RequestIdErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        Object id = webRequest.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (id != null) {
            attributes.put("requestId", id);
        }
        return attributes;
    }
}
