package io.inspector.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * R-AUD-04 (usability W1#6, theme T5): every request carries ONE quotable id, end to end. An
 * inbound {@code X-Request-Id} is honored when it is header/log-safe (gateways and proxies mint
 * them); anything else gets a fresh UUID — never an echo of attacker-controlled bytes. The id is
 *
 * <ol>
 *   <li>echoed as the {@code X-Request-Id} response header on EVERY response,
 *   <li>MDC-bound under {@link #MDC_KEY} so every log line of the request carries it
 *       ({@code logging.pattern.correlation}), and
 *   <li>picked up by the error surfaces — {@link ProblemDetailRequestIdAdvice} on the
 *       handler/exception-handler path, {@link RequestIdErrorAttributes} on the container
 *       {@code /error} path (the bare 403/404 shape) — and by
 *       {@code AuditService.beginPending}, so the id an operator quotes to support finds BOTH
 *       the log lines and the audit rows of the request.
 * </ol>
 *
 * <p>Registered in {@code WebConfig} at highest precedence — ahead of the security chain, so the
 * pre-handler 401/403 legs carry it too — and on the ERROR dispatch as well, so MDC is
 * repopulated (from the request attribute, which survives {@code sendError}) while
 * {@code BasicErrorController} renders {@code /error}.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** MDC key — named for the audit vocabulary: this value IS the audit row's correlationId. */
    public static final String MDC_KEY = "correlationId";

    /** Request attribute carrying the id — survives the sendError → ERROR re-dispatch. */
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    /** Header/log-safe shape; anything else is replaced, never reflected. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false; // rerun on ERROR dispatch: restore MDC + header for the /error rendering
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            String inbound = request.getHeader(HEADER);
            id = inbound != null && SAFE_ID.matcher(inbound).matches()
                    ? inbound
                    : UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
