package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the V7 engine registry against a REAL Postgres 16 (Testcontainers) with
 * {@code inspector.registry.source=db}. Proves what the mocked/pure rungs cannot — that Flyway
 * V1..V7 apply, Hibernate VALIDATES the {@link EngineRegistryRow} against V7 (ddl-auto=validate,
 * iron rule), and {@link RegistryBootstrap} imported the YAML into the empty registry with the
 * fail-closed {@code registry-seed} audit at boot.
 *
 * <p>DB-only (no engine call) — LOCAL-ONLY like {@code ViewStoreIT} (not in ci.yml itClass); the
 * V7↔entity alignment is additionally exercised by every CI integration leg's context startup.
 */
@SpringBootTest
@ActiveProfiles("it-registry")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineRegistryStoreIT {

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
    EngineRegistryStore store;

    @Autowired
    AuditEntryRepository auditEntries;

    @Test
    void bootSeededTheYamlEnginesAsLiveRows() {
        assertThat(store.isEmpty()).isFalse();

        List<EngineConfig> live = store.findLive();
        assertThat(live).extracting(EngineConfig::id).containsExactlyInAnyOrder("engine-a", "engine-7");

        EngineConfig engineA =
                live.stream().filter(e -> e.id().equals("engine-a")).findFirst().orElseThrow();
        assertThat(engineA.enabled()).isTrue(); // enabled YAML → active lifecycle
        assertThat(engineA.environment()).isEqualTo(EngineEnvironment.DEV);
        assertThat(engineA.modeOrDefault()).isEqualTo(EngineMode.READ_WRITE);
        assertThat(engineA.auth().passwordRef()).isEqualTo("ENGINE_A_PASSWORD"); // NAME only

        EngineConfig engine7 =
                live.stream().filter(e -> e.id().equals("engine-7")).findFirst().orElseThrow();
        assertThat(engine7.enabled()).isFalse(); // disabled YAML → disabled lifecycle, still a live row
        assertThat(engine7.modeOrDefault()).isEqualTo(EngineMode.READ_ONLY);
    }

    @Test
    void seedWasAuditedFailClosedWithSecretRefsRedacted() {
        List<AuditEntry> seedRows = auditEntries.findAll().stream()
                .filter(e -> "registry-seed".equals(e.getAction()))
                .toList();

        assertThat(seedRows).hasSize(2);
        assertThat(seedRows).allSatisfy(e -> {
            assertThat(e.getOutcome()).isEqualTo(AuditOutcome.ok);
            assertThat(e.getActor()).isEqualTo("system");
            // The password-ref env-var NAME is run through the denylist redactor (design §7),
            // and no secret VALUE ever appears.
            assertThat(e.getPayload()).doesNotContain("ENGINE_A_PASSWORD").doesNotContain("ENGINE_7_PASSWORD");
        });
        assertThat(seedRows).extracting(AuditEntry::getInstanceId).containsExactlyInAnyOrder("engine-a", "engine-7");
    }
}
