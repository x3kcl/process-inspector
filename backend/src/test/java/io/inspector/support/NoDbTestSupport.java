package io.inspector.support;

import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.InstanceNoteRepository;
import io.inspector.audit.ProtectedInstanceRepository;
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
}
