package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 for {@link AuditEntryRepository#findAttributableActionPoints} (issue #358 item 2,
 * RETRYING-RISK-LANE.md §3.2/§3.3/§10): hand-authored JPQL with an {@code engineId IN (...)}
 * predicate, a {@code [since, until]} range and a {@code bulk:} prefix exclusion — proving it
 * actually filters (as opposed to merely proving the service calls it with the right arguments,
 * which the mocked-repository {@code EpisodeActionAttributionServiceTest} covers) needs a REAL
 * Postgres, exactly the reasoning {@link AuditEntryRepositoryScopeIT} documents for its sibling
 * query. LOCAL-ONLY (not in ci.yml's itClass), same precedent.
 *
 * <p>Requires: docker compose -f docker/docker-compose.dev.yml up -d (context boot only — no
 * engine call is made; this test only needs a Postgres, which Testcontainers supplies itself).
 */
@SpringBootTest(properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditEntryRepositoryAttributionIT {

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
    AuditEntryRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // TRUNCATE (not DELETE) — the V1 append-only guard is a row-level BEFORE DELETE trigger
        // and does not fire on TRUNCATE (AuditEntryRepositoryScopeIT precedent).
        jdbc.execute("TRUNCATE TABLE audit_entry");
    }

    private void seed(String engineId, String action, String outcome, String tsIso) {
        jdbc.update(
                "INSERT INTO audit_entry (id, correlation_id, actor, ts, engine_id, action, outcome,"
                        + " chain_hash) VALUES (gen_random_uuid(), gen_random_uuid()::text, 'alice', ?::timestamptz,"
                        + " ?, ?, ?, 'seed')",
                tsIso,
                engineId,
                action,
                outcome);
    }

    @Test
    void onlyRowsInsideTheWindowOnAHostingEngineComeBack() {
        seed("engine-a", "retry-job", "ok", "2026-08-07T12:00:00Z"); // in window, right engine
        seed("engine-b", "retry-job", "ok", "2026-08-07T12:00:00Z"); // in window, WRONG engine
        seed("engine-a", "retry-job", "ok", "2026-01-01T00:00:00Z"); // right engine, OUT of window

        List<AttributedActionPoint> points = repository.findAttributableActionPoints(
                Set.of("engine-a"),
                Instant.parse("2026-08-07T11:00:00Z"),
                Instant.parse("2026-08-07T13:00:00Z"),
                PageRequest.of(0, 100));

        assertThat(points).extracting(AttributedActionPoint::action).containsExactly("retry-job");
    }

    @Test
    void windowBoundsAreInclusiveAtBothEnds() {
        seed("engine-a", "retry-job", "ok", "2026-08-07T11:00:00Z"); // exactly `since`
        seed("engine-a", "edit-variable", "ok", "2026-08-07T13:00:00Z"); // exactly `until`

        List<AttributedActionPoint> points = repository.findAttributableActionPoints(
                Set.of("engine-a"),
                Instant.parse("2026-08-07T11:00:00Z"),
                Instant.parse("2026-08-07T13:00:00Z"),
                PageRequest.of(0, 100));

        assertThat(points)
                .extracting(AttributedActionPoint::action)
                .containsExactlyInAnyOrder("retry-job", "edit-variable");
    }

    @Test
    void bulkEnvelopeSummaryRowsAreExcluded_onlyPerTargetRowsCount() {
        seed("engine-a", "bulk:retry-job", "ok", "2026-08-07T12:00:00Z"); // envelope — excluded
        seed("engine-a", "retry-job", "ok", "2026-08-07T12:00:01Z"); // per-item — included

        List<AttributedActionPoint> points = repository.findAttributableActionPoints(
                Set.of("engine-a"),
                Instant.parse("2026-08-07T11:00:00Z"),
                Instant.parse("2026-08-07T13:00:00Z"),
                PageRequest.of(0, 100));

        assertThat(points).extracting(AttributedActionPoint::action).containsExactly("retry-job");
    }

    @Test
    void outcomeIsCarriedThroughUnfiltered() {
        seed("engine-a", "retry-job", "ok", "2026-08-07T12:00:00Z");
        seed("engine-a", "retry-job", "failed", "2026-08-07T12:00:01Z");
        seed("engine-a", "edit-variable", "unknown", "2026-08-07T12:00:02Z");

        List<AttributedActionPoint> points = repository.findAttributableActionPoints(
                Set.of("engine-a"),
                Instant.parse("2026-08-07T11:00:00Z"),
                Instant.parse("2026-08-07T13:00:00Z"),
                PageRequest.of(0, 100));

        assertThat(points)
                .extracting(AttributedActionPoint::outcome)
                .containsExactlyInAnyOrder(AuditOutcome.ok, AuditOutcome.failed, AuditOutcome.unknown);
    }
}
