package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.security.reauth.ReauthRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * The re-auth challenge is rendered as a distinguishable 401 (IDP-SECURITY.md §5): the SPA must be
 * able to tell a freshness challenge (run the interstitial + replay the verb) apart from a plain
 * session-expired 401 (redirect to full sign-in). The {@code X-Reauth-Required} header + the
 * {@code reauth-required} body code carry that signal.
 */
class ActionExceptionHandlerTest {

    private final ActionExceptionHandler handler = new ActionExceptionHandler();

    @Test
    void reauthRequiredMapsTo401WithTheDistinguishingMarker() {
        ResponseEntity<ProblemDetail> response = handler.reauthRequired(new ReauthRequiredException(900));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("X-Reauth-Required")).isEqualTo("true");

        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(body.getProperties()).containsEntry("code", "reauth-required");
        assertThat(body.getProperties()).containsEntry("outcome", "refused");
        assertThat(body.getProperties()).containsEntry("freshnessWindowSeconds", 900);
    }
}
