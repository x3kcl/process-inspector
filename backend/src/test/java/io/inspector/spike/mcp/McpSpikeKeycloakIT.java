package io.inspector.spike.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.NoDbTestSupport;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * SPIKE #492 S0 U3a (NOT FOR MERGE) — a REAL Keycloak issuing a per-user, audience-bound access token
 * to a PRE-REGISTERED PUBLIC client by authorization code + PKCE (S256), exactly what an MCP client
 * does; the token is presented as a bearer to the BFF's /mcp on a real embedded Tomcat ({@code oidc}
 * profile, resource-server chain, Boot issuer + audience validation) and the groups resolve through the
 * SAME OidcGroupResolver + file mapping as the SPA login. Tokens are never printed; only claim names
 * and non-secret claim values.
 *
 * <p>Run: {@code mvn -o verify -Dskip.surefire.tests=true -Dit.test=McpSpikeKeycloakIT}. Image override:
 * {@code -Dspike.keycloak.image=quay.io/keycloak/keycloak:26.8.2 -Dspike.keycloak.features=resource-indicators}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "oidc"})
@Import(NoDbTestSupport.class)
class McpSpikeKeycloakIT {

    static final String REALM = "mcp-spike";
    static final String AUDIENCE = "https://inspector.test/mcp";
    static final String REDIRECT = "http://127.0.0.1:33418/callback";
    static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                    System.getProperty("spike.keycloak.image", "quay.io/keycloak/keycloak:25.0"))
            .withExposedPorts(8080)
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/mcp-spike-realm.json"),
                    "/opt/keycloak/data/import/mcp-spike-realm.json")
            .withCommand(command())
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forStatusCode(200));

    static String[] command() {
        String features = System.getProperty("spike.keycloak.features", "");
        return features.isBlank()
                ? new String[] {"start-dev", "--import-realm"}
                : new String[] {"start-dev", "--import-realm", "--features=" + features};
    }

    static {
        KEYCLOAK.start();
    }

    static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("INSPECTOR_OIDC_ISSUER_URI", McpSpikeKeycloakIT::issuer);
        registry.add("INSPECTOR_OIDC_CLIENT_ID", () -> "inspector-bff");
        registry.add("INSPECTOR_OIDC_CLIENT_SECRET", () -> "test-secret");
        registry.add("INSPECTOR_MCP_AUDIENCE", () -> AUDIENCE);
        registry.add("inspector.security.mapping.access-admin-group", () -> "pi-access-admins");
        registry.add("inspector.security.scope-mapping-file", () -> "src/test/resources/spike/mcp-scope-mapping.yml");
    }

    @LocalServerPort
    int port;

    // ---- the MCP client side: authorization code + PKCE, public client, no secret ----------------

    record Tokens(String access, JsonNode claims) {}

    static Tokens pkceLogin(String user, String password, String scope, String resource) throws Exception {
        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorize = issuer() + "/protocol/openid-connect/auth?response_type=code&client_id=mcp-cli"
                + "&redirect_uri=" + enc(REDIRECT) + "&scope=" + enc(scope) + "&state=s1"
                + "&code_challenge=" + challenge + "&code_challenge_method=S256"
                + (resource == null ? "" : "&resource=" + enc(resource));
        HttpClient browser = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpResponse<String> page =
                browser.send(HttpRequest.newBuilder(URI.create(authorize)).build(), BodyHandlers.ofString());
        assertThat(page.statusCode()).as("authorize page").isEqualTo(200);
        Matcher action = Pattern.compile("action=\"([^\"]+)\"").matcher(page.body());
        assertThat(action.find()).isTrue();
        HttpResponse<String> login = browser.send(
                HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(BodyPublishers.ofString("username=" + user + "&password=" + password))
                        .build(),
                BodyHandlers.ofString());
        String location = login.headers()
                .firstValue("Location")
                .orElseThrow(() -> new AssertionError("no redirect after login: " + login.statusCode()));
        assertThat(location).startsWith(REDIRECT);
        Matcher code = Pattern.compile("[?&]code=([^&]+)").matcher(location);
        assertThat(code.find()).isTrue();
        String form = "grant_type=authorization_code&client_id=mcp-cli&redirect_uri=" + enc(REDIRECT) + "&code="
                + code.group(1) + "&code_verifier=" + verifier + (resource == null ? "" : "&resource=" + enc(resource));
        HttpResponse<String> token = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(BodyPublishers.ofString(form))
                                .build(),
                        BodyHandlers.ofString());
        assertThat(token.statusCode())
                .as("token endpoint: %s", redact(token.body()))
                .isEqualTo(200);
        String access = JSON.readTree(token.body()).get("access_token").asText();
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        System.out.println("SPIKE-TOKEN user=" + user + " scope=" + scope + " resource=" + resource + " -> iss="
                + claims.path("iss") + " aud=" + claims.path("aud") + " azp=" + claims.path("azp") + " scope="
                + claims.path("scope") + " groups=" + claims.path("groups") + " preferred_username="
                + claims.path("preferred_username") + " lifetime_s="
                + (claims.path("exp").asLong() - claims.path("iat").asLong()) + " access_token=<redacted>");
        return new Tokens(access, claims);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String redact(String body) {
        return body.replaceAll("\"(access_token|refresh_token|id_token)\":\"[^\"]+\"", "\"$1\":\"<redacted>\"");
    }

    HttpResponse<String> mcp(String body, String bearer) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req.POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
        System.out.println("SPIKE-EXCHANGE POST /mcp " + body + " Authorization="
                + (bearer == null ? "<none>" : "Bearer <redacted>") + "\n  -> " + resp.statusCode()
                + " WWW-Authenticate="
                + resp.headers().firstValue("WWW-Authenticate").orElse(null) + "\n  "
                + resp.body());
        return resp;
    }

    // ---- the experiments ---------------------------------------------------------------------------

    @Test
    void aliceWithTheMcpScopeGetsAnAudienceBoundTokenAndSeesHerEngineOnly() throws Exception {
        Tokens t = pkceLogin("alice", "alice-pw", "openid inspector-mcp", AUDIENCE);
        assertThat(t.claims().path("aud").toString()).contains(AUDIENCE);
        var resp = mcp(McpSpikeDevChainSpringTest.call("whoami_engines"), t.access());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body())
                .contains("\\\"principal\\\":\\\"alice\\\"")
                .contains("\\\"virtual\\\":true")
                .contains("\\\"engines\\\":{\\\"probe-dev\\\":\\\"VIEWER\\\"}");
    }

    @Test
    void aNoRoleUserTokenGetsTheRoleFloorRefusal() throws Exception {
        Tokens t = pkceLogin("bob", "bob-pw", "openid inspector-mcp", AUDIENCE);
        var resp = mcp(McpSpikeDevChainSpringTest.call("whoami_engines"), t.access());
        assertThat(resp.body()).contains("\"isError\":true").contains("no-engine-role");
    }

    @Test
    void aTokenWithoutTheMcpAudienceIsRejected() throws Exception {
        // The RFC 8707 `resource` parameter alone, without the audience scope: on Keycloak 25 the
        // parameter is ignored, so the token is NOT bound to the Inspector and /mcp must refuse it.
        Tokens t = pkceLogin("alice", "alice-pw", "openid", AUDIENCE);
        var resp = mcp(McpSpikeDevChainSpringTest.LIST, t.access());
        System.out.println(
                "SPIKE-RESULT resource-param-only aud=" + t.claims().path("aud") + " -> " + resp.statusCode());
        if (!t.claims().path("aud").toString().contains(AUDIENCE)) {
            assertThat(resp.statusCode()).isEqualTo(401);
            assertThat(resp.headers().firstValue("WWW-Authenticate").orElse("")).contains("invalid_token");
        }
    }

    @Test
    void theSpaConfidentialClientsTokenIsRejected() throws Exception {
        // A token minted for the SPA client (password grant here only to obtain one headlessly).
        HttpResponse<String> token = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(BodyPublishers.ofString("grant_type=password&client_id=inspector-bff"
                                        + "&client_secret=test-secret&username=alice&password=alice-pw&scope=openid"))
                                .build(),
                        BodyHandlers.ofString());
        String access = JSON.readTree(token.body()).get("access_token").asText();
        var resp = mcp(McpSpikeDevChainSpringTest.LIST, access);
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void noTokenGets401WithTheMetadataPointerAndTheMetadataNamesKeycloak() throws Exception {
        var resp = mcp(McpSpikeDevChainSpringTest.LIST, null);
        assertThat(resp.statusCode()).isEqualTo(401);
        String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
        assertThat(challenge).contains("resource_metadata=");
        HttpResponse<String> prm = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://localhost:" + port + "/.well-known/oauth-protected-resource/mcp"))
                                .build(),
                        BodyHandlers.ofString());
        System.out.println("SPIKE-EXCHANGE GET /.well-known/oauth-protected-resource/mcp\n  -> " + prm.statusCode()
                + "\n  " + prm.body());
        assertThat(JSON.readTree(prm.body())
                        .path("authorization_servers")
                        .get(0)
                        .asText())
                .isEqualTo(issuer());
    }

    @Test
    void theIssuerMetadataAsAnMcpClientSeesIt() throws Exception {
        HttpResponse<String> meta = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(issuer() + "/.well-known/openid-configuration"))
                                .build(),
                        BodyHandlers.ofString());
        JsonNode m = JSON.readTree(meta.body());
        System.out.println("SPIKE-AS-METADATA image=" + System.getProperty("spike.keycloak.image", "keycloak:25.0")
                + " code_challenge_methods_supported=" + m.path("code_challenge_methods_supported")
                + " registration_endpoint=" + m.path("registration_endpoint")
                + " device_authorization_endpoint=" + m.path("device_authorization_endpoint")
                + " client_id_metadata_document_supported=" + m.path("client_id_metadata_document_supported")
                + " authorization_response_iss_parameter_supported="
                + m.path("authorization_response_iss_parameter_supported"));
        // Device grant is enabled on the spike client: the endpoint answers for a public client.
        HttpResponse<String> device = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(
                                        m.path("device_authorization_endpoint").asText()))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                // the client enforces PKCE S256, so the device request carries a challenge too
                                .POST(BodyPublishers.ofString("client_id=mcp-cli&scope=openid%20inspector-mcp"
                                        + "&code_challenge_method=S256"
                                        + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
                                .build(),
                        BodyHandlers.ofString());
        System.out.println("SPIKE-DEVICE -> " + device.statusCode() + " "
                + device.body().replaceAll("\"device_code\":\"[^\"]+\"", "\"device_code\":\"<redacted>\""));
        assertThat(device.statusCode()).isEqualTo(200);
    }
}
