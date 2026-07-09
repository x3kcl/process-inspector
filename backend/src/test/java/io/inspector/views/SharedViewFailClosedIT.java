package io.inspector.views;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): publish is AUDITED FAIL-CLOSED (S3, R-SAFE-16 / A4). With a mocked
 * {@code AuditService} whose {@code recordConfigEvent} throws, a publish must refuse (503) AND the
 * {@code shared_view} row must NOT persist — the compensation undoes the write, so no team canon can
 * exist un-audited. This is the "mocked-throws → 503 + no visibility change" test; it needs a real
 * Postgres to prove the row was actually rolled back. LOCAL-ONLY (not in ci.yml itClass).
 */
@SpringBootTest
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedViewFailClosedIT {

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

    @MockBean
    AuditService audit;

    @Autowired
    SharedViewService service;

    @Autowired
    SharedViewRepository repository;

    private static Authentication admin(String user) {
        return new UsernamePasswordAuthenticationToken(user, "x", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void publishRefusesAndRollsBackWhenTheAuditWriteFails() {
        when(audit.recordConfigEvent(anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("postgres down")));

        assertThatThrownBy(() -> service.publish(
                        admin("alice"), "Doomed canon", "engines=orders-prod&status=FAILED", null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");

        // Fail-closed: no team canon may exist that was never audited (compensation removed the row).
        assertThat(repository.findAll()).isEmpty();
    }
}
