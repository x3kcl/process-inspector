package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.security.reauth.ReauthRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

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

    /** One error contract (issue #87 — F4): a bad param is 400 + code, never an ad-hoc map. */
    @Test
    void illegalArgumentMapsToBadRequestWithACode() {
        ProblemDetail body = handler.badRequest(new IllegalArgumentException("q must not be blank"));

        assertThat(body.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(body.getDetail()).isEqualTo("q must not be blank");
        assertThat(body.getProperties()).containsEntry("code", "bad-request");
    }

    /** Every plain ResponseStatusException throw site gets a machine code derived from its status. */
    @Test
    void responseStatusExceptionCarriesItsReasonAndAStatusDerivedCode() {
        ProblemDetail body =
                handler.statusException(new ResponseStatusException(HttpStatus.CONFLICT, "already resolved"));

        assertThat(body.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(body.getDetail()).isEqualTo("already resolved");
        assertThat(body.getProperties()).containsEntry("code", "conflict");
    }

    /** A reasonless status still gets a non-null detail (the status's own reason phrase). */
    @Test
    void responseStatusExceptionWithNoReasonFallsBackToTheReasonPhrase() {
        ProblemDetail body = handler.statusException(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(body.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(body.getDetail()).isEqualTo("Not Found");
        assertThat(body.getProperties()).containsEntry("code", "not-found");
    }
}
