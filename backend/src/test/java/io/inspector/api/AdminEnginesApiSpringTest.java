package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.registry.EngineRegistryRepository;
import io.inspector.support.NoDbTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3: the {@code /api/admin/engines} REGISTRY_ADMIN DOOR matrix + {@code /api/me.registryAdmin}
 * + SSRF-validate-at-write, end-to-end over HTTP with mocked repos (NoDbTestSupport). Proves the
 * fleet grant is ORTHOGONAL: only {@code registry-admin} passes; a per-engine {@code admin} (=the
 * break-glass ADMIN-global shape) and {@code viewer} are refused; the service re-check + lifecycle
 * matrix is the rung-1 {@code EngineRegistryStoreWriteTest}. {@code source=db} here so CRUD is live.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"inspector.registry.source=db", "inspector.registry.egress-allowlist=93.184.216.0/24,127.0.0.0/8"
        })
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class AdminEnginesApiSpringTest {

    @Autowired
    org.springframework.boot.test.web.client.TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    EngineRegistryRepository engines;

    @Autowired
    AuditEntryRepository auditEntries;

    private ResponseEntity<String> as(String user, HttpMethod method, String path, Object body) {
        return rest.withBasicAuth(user, "dev")
                .exchange(path, method, body == null ? null : new HttpEntity<>(body), String.class);
    }

    /* ---------- the door (orthogonal fleet grant) ---------- */

    @Test
    void listIsRegistryAdminOnly() {
        when(engines.findAll()).thenReturn(List.of());

        assertThat(as("registry-admin", HttpMethod.GET, "/api/admin/engines", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // per-engine ADMIN (= break-glass ADMIN-global shape) is refused — orthogonal grant.
        assertThat(as("admin", HttpMethod.GET, "/api/admin/engines", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(as("viewer", HttpMethod.GET, "/api/admin/engines", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity("/api/admin/engines", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void addIsRefusedForANonRegistryAdminAtTheDoor() {
        Map<String, Object> body = Map.of(
                "id",
                "orders",
                "name",
                "Orders",
                "baseUrl",
                "https://93.184.216.34/service",
                "environment",
                "TEST",
                "reason",
                "onboarding a new engine");
        assertThat(as("admin", HttpMethod.POST, "/api/admin/engines", body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* ---------- SSRF validate-at-write, over HTTP ---------- */

    @Test
    void addRejectsAnSsrfBaseUrlWith400() {
        when(engines.existsById(any())).thenReturn(false);
        Map<String, Object> body = Map.of(
                "id",
                "evil",
                "name",
                "Evil",
                "baseUrl",
                "http://169.254.169.254/latest/meta-data",
                "environment",
                "TEST",
                "reason",
                "attempting a metadata SSRF");

        assertThat(as("registry-admin", HttpMethod.POST, "/api/admin/engines", body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void addAcceptsAnAllowlistedPublicUrlWith201() throws Exception {
        when(engines.existsById("orders")).thenReturn(false);
        when(engines.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditEntries.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());
        when(auditEntries.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> body = Map.of(
                "id",
                "orders",
                "name",
                "Orders",
                "baseUrl",
                "https://93.184.216.34/flowable-rest/service",
                "environment",
                "TEST",
                "reason",
                "onboarding the orders engine");

        ResponseEntity<String> res = as("registry-admin", HttpMethod.POST, "/api/admin/engines", body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode dto = mapper.readTree(res.getBody());
        assertThat(dto.get("lifecycle").asText()).isEqualTo("draft"); // born disabled, read-only
        assertThat(dto.get("mode").asText()).isEqualTo("read-only");
        assertThat(dto.get("passwordRefPresent").asBoolean()).isFalse();
    }

    /* ---------- /api/me hint ---------- */

    @Test
    void meExposesTheRegistryAdminHint() throws Exception {
        JsonNode admin = mapper.readTree(
                as("registry-admin", HttpMethod.GET, "/api/me", null).getBody());
        assertThat(admin.get("registryAdmin").asBoolean()).isTrue();

        JsonNode plainAdmin =
                mapper.readTree(as("admin", HttpMethod.GET, "/api/me", null).getBody());
        assertThat(plainAdmin.get("registryAdmin").asBoolean()).isFalse();
    }

    @Test
    void meExposesTheAbsoluteCapGuillotineInstant() throws Exception {
        // Warn-before-guillotine (R-SAFE-07): the dev basic call rides a session (the security
        // context is session-persisted), so /api/me answers its absolute-cap expiry ≈ birth + 24 h
        // (the default; a break-glass session stamps a tighter 4 h override via the same
        // SESSION_CAP_MS_ATTR the /api/me read honours). Presentation only — the
        // AbsoluteSessionTimeoutFilter stays the enforcement.
        JsonNode me =
                mapper.readTree(as("admin", HttpMethod.GET, "/api/me", null).getBody());
        assertThat(me.hasNonNull("sessionExpiresAt")).isTrue();
        java.time.Instant expires =
                java.time.Instant.parse(me.get("sessionExpiresAt").asText());
        java.time.Instant now = java.time.Instant.now();
        assertThat(expires).isAfter(now.plus(java.time.Duration.ofHours(23)));
        assertThat(expires).isBefore(now.plus(java.time.Duration.ofHours(25)));
    }
}
