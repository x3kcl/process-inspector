package io.inspector.support;

import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.InstanceNoteRepository;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.bulk.BulkJobItemRepository;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.registry.EngineRegistryRepository;
import io.inspector.snapshot.SnapshotCountRepository;
import io.inspector.views.RecentSearchRepository;
import io.inspector.views.SavedViewRepository;
import io.inspector.views.SharedViewRepository;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Repository mocks for docker-free contexts (profiles that exclude the DB autoconfig —
 * see the note in each application-*.yml). Mocking OUR OWN Postgres repositories is
 * legitimate at rungs 1–3 (unit-test-patterns); the engine-harness iron rule forbids
 * mocking Flowable, not the BFF's audit store. Everything Flyway/validate/fail-closed
 * runs against real Postgres in the Testcontainers-backed *IT suite.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NoDbTestSupport {

    @Bean
    AuditEntryRepository auditEntryRepository() {
        return Mockito.mock(AuditEntryRepository.class);
    }

    @Bean
    InstanceNoteRepository instanceNoteRepository() {
        return Mockito.mock(InstanceNoteRepository.class);
    }

    @Bean
    ProtectedInstanceRepository protectedInstanceRepository() {
        return Mockito.mock(ProtectedInstanceRepository.class);
    }

    @Bean
    BulkJobRepository bulkJobRepository() {
        return Mockito.mock(BulkJobRepository.class);
    }

    @Bean
    BulkJobItemRepository bulkJobItemRepository() {
        return Mockito.mock(BulkJobItemRepository.class);
    }

    @Bean
    SnapshotCountRepository snapshotCountRepository() {
        return Mockito.mock(SnapshotCountRepository.class);
    }

    @Bean
    SavedViewRepository savedViewRepository() {
        return Mockito.mock(SavedViewRepository.class);
    }

    @Bean
    RecentSearchRepository recentSearchRepository() {
        return Mockito.mock(RecentSearchRepository.class);
    }

    @Bean
    SharedViewRepository sharedViewRepository() {
        return Mockito.mock(SharedViewRepository.class);
    }

    @Bean
    EngineRegistryRepository engineRegistryRepository() {
        return Mockito.mock(EngineRegistryRepository.class);
    }

    /**
     * These docker-free contexts have no DataSource, so Spring Boot never auto-configures a
     * JdbcTemplate — yet audit partition/retention beans (AuditRetentionPurger, the LegalHold
     * service/controller) constructor-inject one. A mock lets those beans wire and stay dormant
     * (@Scheduled jobs don't fire in a short test; no endpoint is exercised here). Real JdbcTemplate
     * behavior is covered by the Testcontainers-backed *IT suite.
     */
    @Bean
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate() {
        return Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
    }
}
