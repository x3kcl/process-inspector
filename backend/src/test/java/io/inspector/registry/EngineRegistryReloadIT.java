package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.inspector.audit.AuditEntryRepository;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the S3 hot-reload seam end-to-end against a REAL Postgres 16
 * (Testcontainers, {@code source: db}). Proves what the mocked rungs cannot — that boot points
 * {@link EngineRegistry} at the DB, and a committed {@code editBaseUrl} fires the
 * {@code @TransactionalEventListener(AFTER_COMMIT)} reload so the in-memory map reflects the new
 * base-URL. DB-only (the edited engine is never dialled) — LOCAL-ONLY like the other DB ITs.
 */
@SpringBootTest
@ActiveProfiles("it-registry")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineRegistryReloadIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    EngineRegistry registry;

    @Autowired
    EngineRegistryStore store;

    @Autowired
    AuditEntryRepository auditEntries;

    @Test
    void bootPointsTheRegistryAtTheDbRows() {
        // engine-a seeded active (enabled), engine-7 seeded disabled.
        assertThat(registry.all())
                .extracting(EngineConfig::id)
                .contains("engine-a")
                .doesNotContain("engine-7");
        // require() is enabled-only; the disabled engine is still resolvable by id (id→name).
        assertThat(registry.resolve("engine-7"))
                .get()
                .satisfies(e -> assertThat(e.enabled()).isFalse());
    }

    @Test
    void editBaseUrlCommitReloadsTheInMemoryRegistry() {
        String newUrl = "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081")
                + "/flowable-rest/service-EDITED";
        assertThat(registry.require("engine-a").baseUrl()).isNotEqualTo(newUrl);

        store.editBaseUrl("engine-a", newUrl, registryAdmin("tester"), "S3 reload seam IT");

        // AFTER_COMMIT reload is synchronous at commit, but bound it with Awaitility (no sleep).
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(registry.require("engine-a").baseUrl()).isEqualTo(newUrl));

        // The edit was audited registry-edit with the before/after (no secret value).
        assertThat(auditEntries.findAll().stream().filter(e -> "registry-edit".equals(e.getAction())))
                .anySatisfy(e -> assertThat(e.getInstanceId()).isEqualTo("engine-a"))
                .allSatisfy(e -> assertThat(e.getPayload()).contains("service-EDITED"));
    }

    /** A REGISTRY_ADMIN principal — editBaseUrl now re-checks the fleet grant in the service (S6). */
    private static Authentication registryAdmin(String name) {
        return new UsernamePasswordAuthenticationToken(
                name, "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_REGISTRY_ADMIN")));
    }
}
