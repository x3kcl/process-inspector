package io.inspector.security;

import static org.springframework.security.config.Customizer.withDefaults;

import io.inspector.security.reauth.ReauthAuthorizationRequestResolver;
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

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

    private final HttpHardeningProperties hardening;
    private final java.time.Clock clock;
    private final OidcProperties oidcProps;
    private final BreakGlassProperties breakGlass;
    private final BreakGlassAuditSink breakGlassSink;
    private final BreakGlassThrottle breakGlassThrottle;
    private final io.inspector.security.mapping.SecurityAlertChannel alertChannel;
    private final io.inspector.audit.AuditService auditService;

    // The sealed break-glass password is an env ref (iron rule) — read here, NEVER logged/stored. A
    // blank value means break-glass is unconfigured (the sealed chain is not wired).
    @org.springframework.beans.factory.annotation.Value("${INSPECTOR_BREAK_GLASS_PASSWORD:}")
    private String breakGlassPassword;

    public SecurityConfig(
            HttpHardeningProperties hardening,
            java.time.Clock clock,
            OidcProperties oidcProps,
            BreakGlassProperties breakGlass,
            BreakGlassAuditSink breakGlassSink,
            BreakGlassThrottle breakGlassThrottle,
            io.inspector.security.mapping.SecurityAlertChannel alertChannel,
            io.inspector.audit.AuditService auditService) {
        this.hardening = hardening;
        this.clock = clock;
        this.oidcProps = oidcProps;
        this.breakGlass = breakGlass;
        this.breakGlassSink = breakGlassSink;
        this.breakGlassThrottle = breakGlassThrottle;
        this.alertChannel = alertChannel;
        this.auditService = auditService;
    }

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
        return http
                // Ingress scrub (M4-CLOSEOUT §2 / D2c): a client-supplied X-Forwarded-User is never
                // trusted or reflected — the BFF mints the outbound header from the audit actor alone.
                .addFilterBefore(new InboundForwardedUserFilter(), SecurityContextHolderFilter.class)
                // Absolute session cap (R-SAFE-07): after SecurityContextHolderFilter so the session
                // exists; on expiry it invalidates + clears and lets the entry point answer 401/redirect.
                .addFilterAfter(
                        new AbsoluteSessionTimeoutFilter(
                                java.time.Duration.ofHours(hardening.sessionAbsoluteCapHoursOrDefault()), clock),
                        SecurityContextHolderFilter.class)
                // Transport/header hardening (IDP-SECURITY.md §8, R-OPS-16), defense-in-depth over the
                // reverse proxy, config-bounded so nothing bricks. nosniff is on by Spring default.
                .headers(headers -> headers.frameOptions(frame -> frame.deny())
                        .referrerPolicy(ref -> ref.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(pp -> pp.policy(hardening.permissionsPolicyOrDefault()))
                        // HSTS opt-in: the proxy owns it by default (deliberately weak stsSeconds) so
                        // the app must never DOUBLE-emit Strict-Transport-Security.
                        .httpStrictTransportSecurity(hsts -> {
                            if (hardening.hstsEnabled()) {
                                hsts.includeSubDomains(false).maxAgeInSeconds(hardening.hstsMaxAgeSOrDefault());
                            } else {
                                hsts.disable();
                            }
                        })
                        // CSP report-only-FIRST (tune against the real bpmn-js/AG-Grid/CodeMirror bundle,
                        // then flip to enforce per deploy); frame-ancestors 'none' is inside the policy.
                        .contentSecurityPolicy(csp -> {
                            csp.policyDirectives(hardening.cspOrDefault());
                            if (hardening.cspReportOnlyOrDefault()) {
                                csp.reportOnly();
                            }
                        }))
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
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
        // Basic auth persists into the HTTP session (Security 6 default is stateless):
        // the SSE stream (v1.x #2) authenticates as a browser EventSource, which cannot
        // send an Authorization header — it rides the JSESSIONID the first authenticated
        // XHR established, matching the oidc chain's session semantics.
        return common(http)
                // Dev Basic re-authenticates every XHR; changeSessionId would re-ID JSESSIONID each
                // time and orphan the long-lived SSE EventSource (⚠️ lead-dev). Keep the session id
                // STABLE on this chain — fixation protection is applied on the oidc chain only.
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.none()))
                .httpBasic(basic -> basic.securityContextRepository(new DelegatingSecurityContextRepository(
                        new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository())))
                .formLogin(withDefaults())
                .build();
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
                        .build(),
                // Fleet REGISTRY_ADMIN (v2 Registry CRUD, R-SAFE-13) — ORTHOGONAL to the ladder, so
                // this user is NOT an engine ADMIN, and the `admin` user above is NOT a registry
                // admin. `.authorities` (not `.roles`) so ROLE_REGISTRY_ADMIN stays outside the
                // ROLE_ADMIN>… hierarchy. Also VIEWER so they can load the SPA to reach the panel.
                User.withUsername("registry-admin")
                        .password(password)
                        .authorities(RbacAuthorizer.REGISTRY_ADMIN_AUTHORITY, "ROLE_" + Role.VIEWER.name())
                        .build(),
                // Fleet ACCESS_ADMIN (v2 IdP-Security, R-SAFE-14) — the apex, ORTHOGONAL to the ladder
                // and to REGISTRY_ADMIN. NOT an engine ADMIN and NOT a registry admin. VIEWER too so
                // they can load the SPA to reach /admin/access.
                User.withUsername("access-admin")
                        .password(password)
                        .authorities(RbacAuthorizer.ACCESS_ADMIN_AUTHORITY, "ROLE_" + Role.VIEWER.name())
                        .build());
    }

    @Bean
    @Profile("oidc")
    SecurityFilterChain oidcChain(
            HttpSecurity http,
            InspectorAuthoritiesMapper authoritiesMapper,
            ClientRegistrationRepository clientRegistrations)
            throws Exception {
        common(http)
                // Session-fixation protection on the oidc chain: a fresh JSESSIONID at login (the SSE
                // stream rides the post-login session, so a single change at login doesn't orphan it).
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .oauth2Login(oauth -> oauth.authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
                                new ReauthAuthorizationRequestResolver(clientRegistrations, oidcProps)))
                        .userInfoEndpoint(user -> user.userAuthoritiesMapper(authoritiesMapper)))
                // Pin the browser (non-/api) entry point to the OIDC redirect EXPLICITLY, so adding the
                // break-glass formLogin below does not hijack it to a /login page (/api stays 401).
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/oidc"),
                        request -> !request.getRequestURI().startsWith("/api/")));

        // Break-glass (R-SAFE-06/11): a sealed local ADMIN account on a distinct /break-glass form
        // login that works when the IdP is down (a local DaoAuthenticationProvider alongside OIDC).
        // Wired ONLY when configured (env password present) — oauth2Login stays the default entry
        // point; the SPA reaches /break-glass from the IdP-unreachable interstitial (S6).
        if (breakGlass.isEnabled() && breakGlassPassword != null && !breakGlassPassword.isBlank()) {
            var sealed = new InMemoryUserDetailsManager(User.withUsername(breakGlass.usernameOrDefault())
                    .password("{noop}" + breakGlassPassword)
                    // ADMIN-global + the break-glass marker; NEVER a fleet grant (§7).
                    .authorities("ROLE_" + Role.ADMIN.name(), RbacAuthorizer.BREAK_GLASS_AUTHORITY)
                    .build());
            var successHandler = new BreakGlassSuccessHandler(
                    auditService,
                    breakGlassSink,
                    alertChannel,
                    breakGlassThrottle,
                    breakGlass.sessionCapHoursOrDefault(),
                    clock);
            // S4: brute-force protection on the sealed door. The throttle FILTER pre-empts a POST with
            // 429+Retry-After while in cooldown (a clean login never hits it); the FAILURE handler counts
            // + alerts on a sustained burst; success resets the counter (in the success handler above).
            var throttleUser = breakGlass.usernameOrDefault();
            http.userDetailsService(sealed)
                    .addFilterBefore(
                            new BreakGlassThrottleFilter(breakGlassThrottle, throttleUser),
                            UsernamePasswordAuthenticationFilter.class)
                    .formLogin(form -> form.loginProcessingUrl("/break-glass")
                            .successHandler(successHandler)
                            .failureHandler(
                                    new BreakGlassFailureHandler(breakGlassThrottle, alertChannel, throttleUser)));
        }
        return http.build();
    }
}
