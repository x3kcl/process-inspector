package io.inspector.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

import io.inspector.audit.AuditEntryRepository;
import io.inspector.security.mapping.AccessGrantProposalRepository;
import io.inspector.security.mapping.GroupFleetGrantRepository;
import io.inspector.security.mapping.GroupScopeGrantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the classpath-scan replacement (issue #90 — F5/F6) actually mocks every
 * {@code JpaRepository} under {@code io.inspector} — including the ones no hand-written
 * {@code @Bean} method here ever covered ({@code AccessGrantProposalRepository} and its two
 * siblings), which is exactly the gap a hand-maintained list silently accumulates.
 */
class NoDbTestSupportTest {

    @Test
    void mocksEveryKnownJpaRepositoryWithoutAnExplicitBeanMethod() {
        try (var ctx = new AnnotationConfigApplicationContext(NoDbTestSupport.class)) {
            assertMocked(ctx.getBean(AuditEntryRepository.class));
            // These three predate no hand-written @Bean in NoDbTestSupport at all — the scan
            // finds them purely by extending JpaRepository, proving a NEW repository needs
            // zero edits here to be usable in a docker-free context.
            assertMocked(ctx.getBean(AccessGrantProposalRepository.class));
            assertMocked(ctx.getBean(GroupFleetGrantRepository.class));
            assertMocked(ctx.getBean(GroupScopeGrantRepository.class));
        }
    }

    @Test
    void stillMocksTheOneExplicitFrameworkTypeException() {
        try (var ctx = new AnnotationConfigApplicationContext(NoDbTestSupport.class)) {
            assertMocked(ctx.getBean(JdbcTemplate.class));
        }
    }

    private static void assertMocked(Object bean) {
        assertThat(mockingDetails(bean).isMock()).isTrue();
    }
}
