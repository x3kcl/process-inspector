package io.inspector.spike.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SPIKE #492 S0 (NOT FOR MERGE) — U2 on the REAL {@code oidc} profile (application-oidc.yml loaded,
 * the oauth2Login SPA chain AND the spike's /mcp resource-server chain built), the
 * MetricsGrantOidcChainSpringTest pattern: static client registration, no IdP contacted. The bearer
 * token is decoded by a stub JwtDecoder (signature/audience validation is U3's Keycloak IT); the
 * principal then flows through the spike's Jwt converter, the REAL RbacAuthorizer and the REAL
 * OidcGroupResolver (issuer pinning on) and the REAL file mapping source (spike/mcp-scope-mapping.yml).
 * Read-scope enforcement is ON (application-oidc.yml default).
 */
@SpringBootTest(
        properties = {
            "INSPECTOR_OIDC_CLIENT_ID=pi-test",
            "INSPECTOR_OIDC_CLIENT_SECRET=unused",
            "INSPECTOR_OIDC_ISSUER_URI=" + McpSpikeOidcChainSpringTest.ISSUER,
            "inspector.security.mapping.access-admin-group=pi-access-admins",
            "inspector.security.scope-mapping-file=src/test/resources/spike/mcp-scope-mapping.yml"
        })
@ActiveProfiles({"test", "oidc"})
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
class McpSpikeOidcChainSpringTest {

    static final String ISSUER = "https://idp.invalid/realms/pi";

    @TestConfiguration
    static class Stubs {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("oidc")
                    .clientId("pi-test")
                    .clientSecret("unused")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri(ISSUER + "/auth")
                    .tokenUri(ISSUER + "/token")
                    .jwkSetUri(ISSUER + "/certs")
                    .issuerUri(ISSUER)
                    .build());
        }

        /** "Token" = base64url("<user>|<issuer>|<group,group>"). Anything else is a bad token. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                String raw;
                try {
                    raw = new String(java.util.Base64.getUrlDecoder().decode(token));
                } catch (IllegalArgumentException e) {
                    throw new BadJwtException("malformed");
                }
                String[] p = raw.split("\\|", -1);
                if (p.length != 3) {
                    throw new BadJwtException("malformed");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject("sub-" + p[0])
                        .claim("preferred_username", p[0])
                        .issuer(p[1])
                        .audience(List.of("inspector-mcp"))
                        .claim("groups", p[2].isEmpty() ? List.of() : List.of(p[2].split(",")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper jackson2;

    static String bearer(String user, String issuer, String groups) {
        // base64url so the RFC 6750 b64token syntax check of the bearer resolver accepts it
        return "Bearer "
                + java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString((user + "|" + issuer + "|" + groups).getBytes());
    }

    MockHttpServletResponse mcp(String body, String authorization) throws Exception {
        MockHttpServletRequestBuilder req = post("/mcp")
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .content(body);
        if (authorization != null) {
            req.header("Authorization", authorization);
        }
        return print(body, mvc.perform(req).andReturn().getResponse());
    }

    static MockHttpServletResponse print(String what, MockHttpServletResponse resp) throws Exception {
        System.out.println("SPIKE-EXCHANGE " + what + "\n  -> " + resp.getStatus() + " WWW-Authenticate="
                + resp.getHeader("WWW-Authenticate") + "\n  " + resp.getContentAsString());
        return resp;
    }

    JsonNode toolData(MockHttpServletResponse resp) throws Exception {
        JsonNode result = jackson2.readTree(resp.getContentAsString()).path("result");
        assertThat(result.path("isError").asBoolean(false))
                .as(result.toString())
                .isFalse();
        return jackson2.readTree(result.path("content").get(0).path("text").asText());
    }

    @Test
    void aViewerOnEngineASeesEngineAOnlyUnderScopeEnforcement() throws Exception {
        var resp = mcp(McpSpikeDevChainSpringTest.call("whoami_engines"), bearer("alice", ISSUER, "team-a-viewers"));
        assertThat(resp.getStatus()).isEqualTo(200);
        JsonNode data = toolData(resp);
        assertThat(data.path("principal").asText()).isEqualTo("alice");
        assertThat(data.path("authType").asText()).isEqualTo("OAuth2AuthenticationToken");
        assertThat(data.path("scopeEnforced").asBoolean()).isTrue();
        assertThat(data.path("engines").properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactly("probe-dev");
        assertThat(data.path("engines").path("probe-dev").asText()).isEqualTo("VIEWER");
    }

    @Test
    void aGlobalAdminGroupSeesEveryEngine() throws Exception {
        JsonNode data = toolData(
                mcp(McpSpikeDevChainSpringTest.call("whoami_engines"), bearer("root", ISSUER, "flowable-admins")));
        assertThat(data.path("engines").path("probe-dev").asText()).isEqualTo("ADMIN");
        assertThat(data.path("engines").path("probe-prod").asText()).isEqualTo("ADMIN");
    }

    @Test
    void aForeignIssuerResolvesNoGroupsSoNoRole() throws Exception {
        // Issuer pinning (OidcGroupResolver) still bites through the spike converter.
        var resp = mcp(
                McpSpikeDevChainSpringTest.call("whoami_engines"),
                bearer("mallory", "https://evil.invalid", "flowable-admins"));
        assertThat(resp.getContentAsString()).contains("no-engine-role").doesNotContain("probe-dev");
    }

    @Test
    void aNoRoleUserGetsTheRoleFloorRefusal() throws Exception {
        var resp = mcp(McpSpikeDevChainSpringTest.call("whoami_engines"), bearer("bob", ISSUER, "no-such-team"));
        assertThat(resp.getStatus()).isEqualTo(200); // a tool error is a JSON-RPC result, not an HTTP error
        assertThat(resp.getContentAsString()).contains("\"isError\":true").contains("no-engine-role");
    }

    @Test
    void preAuthorizeOnAToolIsHonouredForABearerViewer() throws Exception {
        var resp = mcp(McpSpikeDevChainSpringTest.call("spike_admin_only"), bearer("alice", ISSUER, "team-a-viewers"));
        assertThat(resp.getContentAsString())
                .as("@PreAuthorize on @McpTool must not be silently ignored")
                .doesNotContain("secret-admin-payload")
                .contains("Access Denied");
    }

    @Test
    void anUnauthenticatedMcpRequestGets401WithTheResourceMetadataChallenge() throws Exception {
        var resp = mcp(McpSpikeDevChainSpringTest.LIST, null);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getHeader("WWW-Authenticate"))
                .startsWith("Bearer")
                .contains("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"");
    }

    @Test
    void aBadTokenGets401InvalidToken() throws Exception {
        var resp = mcp(McpSpikeDevChainSpringTest.LIST, "Bearer not-a-token");
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getHeader("WWW-Authenticate")).contains("error=\"invalid_token\"");
    }

    @Test
    void theProtectedResourceMetadataIsServed() throws Exception {
        var resp = print(
                "GET /.well-known/oauth-protected-resource/mcp",
                mvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                        .andReturn()
                        .getResponse());
        assertThat(resp.getStatus()).isEqualTo(200);
        JsonNode prm = jackson2.readTree(resp.getContentAsString());
        assertThat(prm.path("resource").asText()).isEqualTo("http://localhost/mcp");
        assertThat(prm.path("authorization_servers").get(0).asText()).isEqualTo(ISSUER);
        assertThat(prm.path("tls_client_certificate_bound_access_tokens").asBoolean())
                .isFalse();
    }

    @Test
    void anSpaOidcSessionPrincipalAlsoResolvesInsideTheTool() throws Exception {
        // The SPA-shaped principal (OidcUser, groups on the ID token) injected by spring-security-test.
        // Proves RbacAuthorizer's OidcUser branch works inside a tool too; whether a REAL browser session
        // cookie may ride /mcp is a separate question (the stateless chain never reads the session).
        var req = post("/mcp")
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .content(McpSpikeDevChainSpringTest.call("whoami_engines"))
                .with(oidcLogin()
                        .idToken(t -> t.subject("carol").issuer(ISSUER).claim("groups", List.of("team-a-viewers"))));
        var resp = print(
                "POST /mcp whoami_engines as oidcLogin(carol)",
                mvc.perform(req).andReturn().getResponse());
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(toolData(resp).path("engines").properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactly("probe-dev");
    }
}
