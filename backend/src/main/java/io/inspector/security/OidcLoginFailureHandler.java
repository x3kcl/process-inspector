package io.inspector.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Counts failed OIDC logins on the handler oauth2Login already calls (#566). Not a second filter.
 * The redirect stays {@code /login?error}, which is Spring's default for this chain.
 */
@Component
@Profile("oidc")
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private final Counter failures;
    private final AuthenticationFailureHandler delegate = new SimpleUrlAuthenticationFailureHandler("/login?error");

    public OidcLoginFailureHandler(MeterRegistry metrics) {
        this.failures = Counter.builder("oidc_login_failures_total")
                .description("OIDC login attempts that failed")
                .register(metrics);
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        failures.increment();
        delegate.onAuthenticationFailure(request, response, exception);
    }
}
