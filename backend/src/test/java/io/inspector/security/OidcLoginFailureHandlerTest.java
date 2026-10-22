package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OidcLoginFailureHandlerTest {

    @Test
    void countsTheFailureAndKeepsTheLoginErrorRedirect() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        OidcLoginFailureHandler handler = new OidcLoginFailureHandler(metrics);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "idp down"));

        assertThat(metrics.counter("oidc_login_failures_total").count()).isEqualTo(1);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }
}
