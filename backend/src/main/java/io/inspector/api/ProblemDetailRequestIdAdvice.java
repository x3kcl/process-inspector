package io.inspector.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * R-AUD-04 (usability W1#6): every {@link ProblemDetail} the BFF writes — the whole
 * {@link ActionExceptionHandler} guard ladder, controller-local handlers, and any future advice —
 * gains an additive {@code requestId} property matching the {@code X-Request-Id} response header,
 * in ONE place instead of per-handler. A single {@code instanceof} decides, so non-problem bodies
 * (DTOs, SSE) pass through untouched.
 */
@RestControllerAdvice
public class ProblemDetailRequestIdAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // ProblemDetail hides inside ResponseEntity<> on some handlers — the declared return
        // type can't decide, so accept and let the cheap instanceof below do it.
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("requestId"))
                && request instanceof ServletServerHttpRequest servletRequest) {
            Object id = servletRequest.getServletRequest().getAttribute(RequestIdFilter.ATTRIBUTE);
            if (id != null) {
                problem.setProperty("requestId", id.toString());
            }
        }
        return body;
    }
}
