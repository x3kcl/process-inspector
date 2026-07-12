package io.inspector.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Prime the {@code XSRF-TOKEN} cookie on every response (#118 items 1 &amp; 2). Spring Security 6
 * loads the CSRF token LAZILY (deferred): with {@code CookieCsrfTokenRepository} +
 * {@code CsrfTokenRequestAttributeHandler}, the cookie is written only when something actually reads
 * the token during request handling. A plain GET reads nothing, so the cookie was never reliably
 * set — and the FIRST unsafe request after a cross-origin sign-in (a search POST, a mutation) fired
 * before any cookie existed, so the {@code cookieCsrf} middleware attached no {@code X-XSRF-TOKEN}
 * and Spring answered a bare 403 that a manual reload silently "fixed". PR #108 added the header
 * echo; this closes the cookie-priming race under it.
 *
 * <p>The fix is the canonical Spring-Security SPA pattern: materialize the deferred token
 * ({@code getToken()}) on every request so the repository writes the cookie — after which the SPA's
 * ordinary boot GET (health, {@code /api/me}) always leaves a usable {@code XSRF-TOKEN} behind. It
 * never masks a real authorization 403 (it only ensures the cookie exists; it changes no decision).
 * The token is absent on CSRF-exempt requests (an {@code Authorization}-header basic call) — a
 * null-guard leaves those untouched (basic-per-request needs no cookie).
 */
public final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) {
            // Force the deferred token to render → CookieCsrfTokenRepository writes the XSRF-TOKEN cookie.
            csrfToken.getToken();
        }
        chain.doFilter(request, response);
    }
}
