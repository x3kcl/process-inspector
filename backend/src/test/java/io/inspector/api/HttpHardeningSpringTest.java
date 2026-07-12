package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.NoDbTestSupport;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3 (IDP-SECURITY.md §8/§3.10, R-OPS-16): the transport/header posture, the fail-closed verb
 * gate, and dev-Basic session stability over real HTTP against the `test` registry twins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class HttpHardeningSpringTest {

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void securityHeadersAreSetAndHstsIsOffByDefault() {
        HttpHeaders h = as("admin").getForEntity("/api/engines", String.class).getHeaders();
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(h.getFirst("Permissions-Policy")).contains("geolocation=()");
        // CSP now ENFORCES (S5): the enforcing header is present, the report-only one absent.
        assertThat(h.getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(h.getFirst("Content-Security-Policy-Report-Only")).isNull();
        // HSTS is the proxy's job — the app must not double-emit it.
        assertThat(h.getFirst("Strict-Transport-Security")).isNull();
    }

    @Test
    void unknownVerbFailsClosedAs404NotASilentAllow() {
        // The verb-existence interceptor answers 404 BEFORE @PreAuthorize; the gate never fails open.
        ResponseEntity<String> resp = as("admin")
                .postForEntity(
                        "/api/instances/probe-dev/pi-1/actions/reticulate-splines",
                        Map.of("jobId", "j1"),
                        String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void knownButForbiddenVerbIsACleanForbidden() {
        // A KNOWN verb the role can't run is 403 (fail-closed gate), distinct from the 404 typo path.
        ResponseEntity<String> resp = as("viewer")
                .postForEntity("/api/instances/probe-dev/pi-1/actions/terminate-delete", Map.of(), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void jsessionidIsStableAcrossConsecutiveBasicXhrs() throws Exception {
        // The dev Basic chain re-authenticates every XHR; with sessionFixation().none() the JSESSIONID
        // must NOT rotate, or the long-lived SSE EventSource would be orphaned (⚠️ lead-dev).
        HttpClient client =
                HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:dev".getBytes());

        first(client, auth); // establishes the session cookie
        String firstId = jsessionId(client);
        assertThat(firstId).as("a session cookie is issued").isNotNull();

        first(client, auth); // second XHR reusing the cookie
        assertThat(jsessionId(client)).as("JSESSIONID does not rotate").isEqualTo(firstId);
    }

    private void first(HttpClient client, String auth) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/engines"))
                .header("Authorization", auth)
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
    }

    private static String jsessionId(HttpClient client) {
        return client.cookieHandler()
                .flatMap(cm -> ((CookieManager) cm)
                        .getCookieStore().getCookies().stream()
                                .filter(c -> c.getName().equals("JSESSIONID"))
                                .map(java.net.HttpCookie::getValue)
                                .findFirst())
                .orElse(null);
    }
}
