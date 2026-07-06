package io.inspector.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Dual auth profile (SPEC §10, ARCH §5): the default (dev) chain authenticates with HTTP
 * Basic + form login against in-memory ladder users; the {@code oidc} profile (prod)
 * authenticates via oauth2-client login, with IdP groups mapped through the BFF-owned
 * scope mapping ({@link InspectorAuthoritiesMapper}).
 *
 * Both chains share the rules: everything requires authentication except health + login
 * plumbing; RBAC tiers are enforced per endpoint via {@code @PreAuthorize} + the scoped
 * guard layer (BFF-side — the UI's greying is a mirror, never the gate). CSRF uses the
 * SPA cookie repository; requests authenticating with an Authorization header (Basic —
 * never browser-ambient) are exempt.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** The layered ladder (R-SAFE-01): a higher role covers every lower one. */
    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_OPERATOR
                ROLE_OPERATOR > ROLE_RESPONDER
                ROLE_RESPONDER > ROLE_VIEWER
                """);
    }

    private HttpSecurity common(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(request -> request.getHeader("Authorization") != null))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/actuator/health/**",
                                "/error",
                                "/login",
                                // R-SEM-15: the OpenAPI contract feeds frontend codegen; it
                                // describes the surface (no data, no secrets) so it stays open.
                                "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // API routes answer a hard 401 — never a login-page redirect (the SPA owns
                // the login flow; a 302→HTML would masquerade as a 200 to API clients).
                .exceptionHandling(handling -> handling.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> request.getRequestURI().startsWith("/api/")));
    }

    @Bean
    @Profile("!oidc")
    SecurityFilterChain devChain(HttpSecurity http) throws Exception {
        return common(http).httpBasic(withDefaults()).formLogin(withDefaults()).build();
    }

    /**
     * Dev/test logins, one per ladder rung. The password is a dev convenience
     * ({@code INSPECTOR_DEV_PASSWORD}, default "dev") — this chain never runs in prod,
     * where the {@code oidc} profile replaces it entirely.
     */
    @Bean
    @Profile("!oidc")
    UserDetailsService devUsers(SecurityProperties props) {
        String password = "{noop}" + props.devPasswordOrDefault();
        return new InMemoryUserDetailsManager(
                User.withUsername("viewer")
                        .password(password)
                        .roles(Role.VIEWER.name())
                        .build(),
                User.withUsername("responder")
                        .password(password)
                        .roles(Role.RESPONDER.name())
                        .build(),
                User.withUsername("operator")
                        .password(password)
                        .roles(Role.OPERATOR.name())
                        .build(),
                User.withUsername("admin")
                        .password(password)
                        .roles(Role.ADMIN.name())
                        .build());
    }

    @Bean
    @Profile("oidc")
    SecurityFilterChain oidcChain(HttpSecurity http, InspectorAuthoritiesMapper authoritiesMapper) throws Exception {
        return common(http)
                .oauth2Login(oauth -> oauth.userInfoEndpoint(user -> user.userAuthoritiesMapper(authoritiesMapper)))
                .build();
    }
}
