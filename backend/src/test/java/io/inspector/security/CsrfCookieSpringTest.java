package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.NoDbTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * #118 items 1 &amp; 2: the {@link CsrfCookieFilter} must PRIME the {@code XSRF-TOKEN} cookie on an
 * ordinary (cookie-session) request, so the SPA's first post-login unsafe request never races an
 * unset cookie. Spring's deferred CSRF token would otherwise leave the cookie unwritten on a plain
 * GET. Real-HTTP rung 3 (a servlet container actually renders the Set-Cookie).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class CsrfCookieSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void anOrdinaryCookieSessionRequestPrimesTheXsrfTokenCookie() {
        // No Authorization header ⇒ the cookie-session (SPA) path, where CSRF applies. Even this
        // permitAll GET must leave a usable XSRF-TOKEN behind for the next unsafe request.
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> setCookies = response.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        assertThat(setCookies).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
    }

    @Test
    void aPrimedTokenLetsTheFollowUpUnsafeCookieRequestPassCsrf() {
        // The race-fix end-to-end: prime the cookie on a GET, then echo it as BOTH the cookie and the
        // X-XSRF-TOKEN header on an unsafe cookie-session request — it must clear CSRF (not the bare
        // 403 the un-primed first request used to hit). It still 401s on auth (no session), NOT 403 on
        // CSRF — proving CSRF was satisfied by the primed token.
        ResponseEntity<String> primed = rest.getForEntity("/v3/api-docs", String.class);
        String xsrfCookie = primed.getHeaders().get("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow();
        String token = xsrfCookie.substring("XSRF-TOKEN=".length(), xsrfCookie.indexOf(';'));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
        ResponseEntity<String> mutation =
                rest.exchange("/api/search", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);

        // Not a CSRF 403 — the primed token cleared CSRF. (401: no authenticated session on this call.)
        assertThat(mutation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
