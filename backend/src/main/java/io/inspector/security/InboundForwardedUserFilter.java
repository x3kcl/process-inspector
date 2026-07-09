package io.inspector.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Scrubs any client-supplied inbound {@code X-Forwarded-User} at ingress (M4-CLOSEOUT §2 / D2c).
 *
 * <p>The BFF alone mints the outbound {@code X-Forwarded-User} — from the audit-row actor, via the
 * write client's interceptor — so it is NEVER derived from an inbound header. This filter makes that
 * normative: under a {@code forward-headers-strategy}-on proxy a client-set {@code X-Forwarded-User}
 * would otherwise be observable/reflectable, and the header is forgeable at a directly-reachable
 * engine. Blanking it before any downstream code can read it removes the reflection vector
 * (defense-in-depth alongside the interceptor, which always {@code set()}s a fresh value).
 */
public class InboundForwardedUserFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Forwarded-User";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new ScrubbedRequest(request), response);
    }

    /** A request view where {@code X-Forwarded-User} is invisible, however the client spelled it. */
    private static final class ScrubbedRequest extends HttpServletRequestWrapper {
        ScrubbedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(HEADER::equalsIgnoreCase);
            return Collections.enumeration(names);
        }
    }
}
