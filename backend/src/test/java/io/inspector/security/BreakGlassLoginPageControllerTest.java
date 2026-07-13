package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * Rung 1: {@code GET /break-glass} (#94). The unconfigured-deployment gate mirrors
 * {@link SecurityConfig}'s own POST wiring exactly, and the rendered page never depends on the
 * SPA's JS-driven CSRF echo (unlike {@code client.ts}'s {@code xsrfHeaderValue}) — the token rides
 * a server-rendered hidden field a plain browser can submit unaided.
 */
class BreakGlassLoginPageControllerTest {

    private static final CsrfToken TOKEN = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value-123");

    @Test
    void a404IsReturnedWhenBreakGlassIsNotEnabled() {
        var controller =
                new BreakGlassLoginPageController(new BreakGlassProperties(false, null, null, null), "sealed-password");
        var request = new MockHttpServletRequest();
        request.setAttribute(CsrfToken.class.getName(), TOKEN);

        var response = controller.loginPage(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void a404IsReturnedWhenEnabledButThePasswordEnvRefIsBlank() {
        // Mirrors SecurityConfig's own gate exactly: enabled=true alone never wires the door.
        var controller = new BreakGlassLoginPageController(new BreakGlassProperties(true, null, null, null), "");

        var response = controller.loginPage(new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aConfiguredDeploymentServesAFormWithTheRealCsrfHiddenFieldAndNoJsDependency() {
        var controller =
                new BreakGlassLoginPageController(new BreakGlassProperties(true, null, null, null), "sealed-password");
        var request = new MockHttpServletRequest();
        request.setAttribute(CsrfToken.class.getName(), TOKEN);

        var response = controller.loginPage(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("<form method=\"post\" action=\"/break-glass\">");
        // The CSRF token is a server-rendered hidden field — a plain <form> POST (no JS) can
        // satisfy it, unlike the SPA's cookie-read/header-echo pattern (#94's confirmed gap).
        assertThat(body).contains("<input type=\"hidden\" name=\"_csrf\" value=\"token-value-123\">");
        assertThat(body).contains("name=\"username\"").contains("name=\"password\"");
        assertThat(body).doesNotContain("<script");
    }

    @Test
    void aMissingCsrfTokenAttributeStillRendersAWorkingFormMinusTheHiddenField() {
        // Defensive only — CsrfCookieFilter always primes the attribute in the real chain
        // (proven by CsrfCookieSpringTest); this just proves the page never NPEs without it.
        var controller =
                new BreakGlassLoginPageController(new BreakGlassProperties(true, null, null, null), "sealed-password");

        var response = controller.loginPage(new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("hidden");
    }
}
