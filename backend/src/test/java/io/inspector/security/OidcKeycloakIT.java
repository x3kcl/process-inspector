package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Rung 4 (engine-harness), LOCAL-ONLY — the REAL {@code oidc} chain against a genuine Keycloak
 * issuer + a Testcontainers Postgres (like the other DB/container ITs, this is NOT in ci.yml's
 * itClass matrix; CI proves the resolver logic at rung 1 in {@link OidcGroupResolverTest}). A
 * stub can't exercise what matters here (IDP-SECURITY.md §4): that {@code application-oidc.yml}
 * parses and the chain boots against a live issuer, that discovery/JWKS/PKCE wire against a real
 * IdP, and that a real Keycloak {@code groups} claim is a JSON array flowing through the single
 * authoritative {@link OidcGroupResolver} — with issuer pinning rejecting a foreign issuer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"oidc", "it-oidc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OidcKeycloakIT {

    private static final String REALM = "inspector";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:25.0")
            .withExposedPorts(8080)
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/inspector-realm.json"),
                    "/opt/keycloak/data/import/inspector-realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forStatusCode(200));

    static {
        POSTGRES.start();
        KEYCLOAK.start();
    }

    private static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Satisfy application-oidc.yml's env placeholders with the live container values.
        registry.add("spring.security.oauth2.client.provider.oidc.issuer-uri", OidcKeycloakIT::issuer);
        registry.add("spring.security.oauth2.client.registration.oidc.client-id", () -> "inspector-bff");
        registry.add("spring.security.oauth2.client.registration.oidc.client-secret", () -> "test-secret");
        registry.add("inspector.security.oidc.issuer", OidcKeycloakIT::issuer);
    }

    @LocalServerPort
    int port;

    @Autowired
    ClientRegistrationRepository clientRegistrations;

    @Autowired
    OidcGroupResolver groupResolver;

    @Test
    void theClientRegistrationResolvesFromTheRealDiscoveryDocument() {
        ClientRegistration reg = clientRegistrations.findByRegistrationId("oidc");
        assertThat(reg).isNotNull();
        assertThat(reg.getProviderDetails().getIssuerUri()).isEqualTo(issuer());
        assertThat(reg.getScopes()).contains("openid", "profile", "groups");
    }

    @Test
    void anUnauthenticatedRequestStartsTheOauth2LoginRedirect() throws Exception {
        // The SPA-facing path (not /api, not permitted) hands off to oauth2Login.
        HttpResponse<String> resp = noFollow("/");
        assertThat(resp.statusCode()).isEqualTo(302);
        assertThat(resp.headers().firstValue("Location").orElse("")).contains("/oauth2/authorization/oidc");
    }

    @Test
    void theAuthorizationRequestCarriesPkce() throws Exception {
        // The handoff yields a 302 to Keycloak's authorize endpoint WITH a PKCE challenge
        // (confidential clients don't get PKCE unless it's wired — IDP-SECURITY.md §4).
        HttpResponse<String> resp = noFollow("/oauth2/authorization/oidc");
        assertThat(resp.statusCode()).isEqualTo(302);
        String location = resp.headers().firstValue("Location").orElse("");
        assertThat(location).startsWith(issuer() + "/protocol/openid-connect/auth");
        assertThat(location).contains("code_challenge=").contains("code_challenge_method=S256");
        // A normal login carries no max_age — no per-login MFA storm (S5).
        assertThat(location).doesNotContain("max_age");
    }

    @Test
    void theDangerousSetReauthForcesMaxAgeAndPromptLogin() throws Exception {
        // The SPA replays a dangerous verb by re-initiating login with the reauth marker; the resolver
        // then injects max_age (the freshness window) + prompt=login so the IdP forces a fresh
        // auth_time rather than silently returning the stale SSO session (S5, IDP-SECURITY.md §5).
        HttpResponse<String> resp = noFollow("/oauth2/authorization/oidc?reauth=true");
        assertThat(resp.statusCode()).isEqualTo(302);
        String location = resp.headers().firstValue("Location").orElse("");
        assertThat(location).startsWith(issuer() + "/protocol/openid-connect/auth");
        assertThat(location).contains("max_age=").contains("prompt=login");
        // PKCE MUST survive the reauth rewrite — the resolver copies the existing additionalParameters
        // (where the code_challenge lives) before adding max_age/prompt (Copilot S5a review).
        assertThat(location).contains("code_challenge=").contains("code_challenge_method=S256");
    }

    /** A GET that never follows redirects — so we assert the 302 the BFF itself emits. */
    private HttpResponse<String> noFollow(String path) throws Exception {
        // Accept: text/html so oauth2Login's browser entry point engages (a bare */* request
        // falls through to the /api 401 handler — the SPA navigations we model are HTML GETs).
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/html")
                .GET()
                .build();
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request, BodyHandlers.ofString());
    }

    @Test
    void aRealKeycloakGroupsClaimIsAnArrayAndFlowsThroughTheResolver() throws Exception {
        Jwt idToken = passwordGrantIdToken("alice", "alice-pw");

        // A REAL IdP emits groups as a JSON array — the shape our resolver trusts.
        Map<String, Object> claims = idToken.getClaims();
        assertThat(claims.get("groups")).isInstanceOf(List.class);

        assertThat(groupResolver.resolveForLogin(claims, issuer(), idToken.getSubject()))
                .contains("flowable-admins", "orders-l1");

        // Issuer pinning: the very same claims presented as if from a foreign tenant → zero groups.
        assertThat(groupResolver.resolveForCheck(claims, "https://login.microsoftonline.com/evil/v2.0", "alice"))
                .isEmpty();
    }

    /** Direct-access (password) grant → a real id token, decoded + validated against the live issuer. */
    private Jwt passwordGrantIdToken(String user, String password) throws Exception {
        // groups is a DEFAULT client scope in the realm, so it's emitted without an explicit request.
        String form = "grant_type=password&client_id=inspector-bff&client_secret=test-secret" + "&username=" + user
                + "&password=" + password + "&scope=openid";
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("token endpoint: %s", resp.body()).isEqualTo(200);
        JsonNode json = new ObjectMapper().readTree(resp.body());
        String idToken = json.get("id_token").asText();
        // Building the decoder from the issuer location proves discovery + JWKS + issuer validation
        // all wire against the real IdP (a token with a mismatched iss would be rejected here).
        JwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer());
        return decoder.decode(idToken);
    }
}
