package io.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.EngineSeed;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the v2/M4 snapshot backbone against REAL flowable-rest 6.8 AND a REAL
 * Postgres 16 (Testcontainers) — the sampler runs the live Stage-0 aggregation on the BACKGROUND
 * lane and upserts through the actual JDBC path into the partitioned {@code triage_snapshot} table
 * that Flyway's V5 authored and Hibernate validated. Proves the three things unit tests can't:
 * a live sample lands, the {@code ON CONFLICT} upsert is idempotent against the partitioned unique
 * index, and the create-ahead monthly partitions exist alongside the DEFAULT catch-all.
 *
 * <p>The sampler drives ONE cycle explicitly ({@code sampleOnce()}) rather than waiting on the
 * scheduler — deterministic, no sleeps. LOCAL-ONLY (not in ci.yml's itClass, like the other
 * DB+engine ITs); CI covers the logic via the rung-1 suite.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotSamplerIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";

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
    SnapshotSampler sampler;

    @Autowired
    SnapshotCountRepository repository;

    @Autowired
    SnapshotPartitionMaintainer maintainer;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    void requireEngine() {
        // Fail loudly (never a silent skip) if the flowable-6 stack is not up.
        EngineSeed.requireReachable(ENGINE, "");
    }

    @Test
    void aLiveSampleLandsTheStatusLanesForTheEngine() {
        int written = sampler.sampleOnce();

        assertThat(written).as("the live aggregation produced lane counts").isPositive();
        for (SnapshotLane lane : List.of(SnapshotLane.ACTIVE, SnapshotLane.SUSPENDED, SnapshotLane.COMPLETED)) {
            assertThat(repository.findByEngineIdAndLaneAndSampledAtGreaterThanEqualOrderBySampledAtDesc(
                            "engine-a", lane, Instant.EPOCH))
                    .as("lane %s should be sampled for engine-a", lane)
                    .isNotEmpty();
        }
    }

    @Test
    void upsertIsIdempotentAgainstThePartitionedUniqueIndex() {
        // A far-future bucket with no monthly partition exercises the DEFAULT catch-all AND the
        // partitioned unique index as the ON CONFLICT arbiter — date-independent.
        Instant bucket = Instant.parse("2099-01-01T00:00:00Z");

        repository.upsert("idem-probe", SnapshotLane.ACTIVE.name(), 5, bucket);
        repository.upsert("idem-probe", SnapshotLane.ACTIVE.name(), 9, bucket);

        assertThat(repository.findByEngineIdAndSampledAtOrderByLane("idem-probe", bucket))
                .singleElement()
                .satisfies(row -> assertThat(row.getCount()).isEqualTo(9L));
    }

    @Test
    void maintainerCreatesTheCurrentAndNextMonthPartitionsAheadOfTheDefault() {
        maintainer.maintain(); // idempotent — also ran at ApplicationReadyEvent

        YearMonth thisMonth = YearMonth.from(LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC));
        assertThat(childPartitions())
                .contains(
                        SnapshotPartitions.name(thisMonth),
                        SnapshotPartitions.name(thisMonth.plusMonths(1)),
                        "triage_snapshot_default");
    }

    private List<String> childPartitions() {
        return jdbc.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'triage_snapshot'
                """, String.class);
    }
}
