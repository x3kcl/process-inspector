package io.inspector.support;

import java.beans.Introspector;
import java.util.List;
import java.util.function.Supplier;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence stand-ins for docker-free contexts (profiles that exclude the DB autoconfig —
 * see the note in each application-it*.yml). Mocking OUR OWN Postgres repositories is
 * legitimate at rungs 1–3 (unit-test-patterns); the engine-harness iron rule forbids
 * mocking Flowable, not the BFF's audit store. Everything Flyway/validate/fail-closed
 * runs against real Postgres in the Testcontainers-backed *IT suite.
 *
 * <p>Test-support consolidation (F5/F6, issue #90): every {@code JpaRepository} interface under
 * {@code io.inspector} is discovered by a classpath scan and mocked automatically — a NEW
 * repository no longer needs a hand-added {@code @Bean} here. The failure mode this replaced:
 * forgetting that edit broke context refresh for ALL 33 docker-free test classes at once with a
 * diffuse {@code NoSuchBeanDefinitionException}, one per dependent context, on whichever ran
 * first — nine years^Wcommits of git history show a 1:1 "new repo ⇒ new manual mock" pattern.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NoDbTestSupport {

    private static final String SCAN_PACKAGE = "io.inspector";

    @Bean
    static BeanDefinitionRegistryPostProcessor mockEveryJpaRepository() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                for (Class<?> repository : jpaRepositoryInterfaces()) {
                    registerMock(registry, repository);
                }
            }

            private <T> void registerMock(BeanDefinitionRegistry registry, Class<T> repository) {
                String beanName = Introspector.decapitalize(repository.getSimpleName());
                if (registry.containsBeanDefinition(beanName)) {
                    return; // a real definition (or an explicit test override) wins
                }
                Supplier<T> mockSupplier = () -> Mockito.mock(repository);
                registry.registerBeanDefinition(beanName, new RootBeanDefinition(repository, mockSupplier));
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                // Registration happens above, before any bean is instantiated — nothing to do here.
            }
        };
    }

    /**
     * Every {@code interface X extends JpaRepository<...>} under {@link #SCAN_PACKAGE}. The
     * default {@link ClassPathScanningCandidateComponentProvider} filter rejects interfaces
     * ({@code isConcrete()} is false for one) — {@code isIndependent()} is the correct relaxed
     * check here (matches Spring Data's own repository scanner, which faces the identical
     * problem for the identical reason).
     */
    private static List<Class<?>> jpaRepositoryInterfaces() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(JpaRepository.class));
        return scanner.findCandidateComponents(SCAN_PACKAGE).stream()
                .map(NoDbTestSupport::loadClass)
                .toList();
    }

    private static Class<?> loadClass(BeanDefinition candidate) {
        try {
            return Class.forName(candidate.getBeanClassName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("repository scan found an unloadable class", e);
        }
    }

    /**
     * These docker-free contexts have no DataSource, so Spring Boot never auto-configures a
     * JdbcTemplate — yet audit partition/retention beans (AuditRetentionPurger, the LegalHold
     * service/controller) constructor-inject one. A mock lets those beans wire and stay dormant
     * (@Scheduled jobs don't fire in a short test; no endpoint is exercised here). Real JdbcTemplate
     * behavior is covered by the Testcontainers-backed *IT suite. Unlike the repositories above,
     * this is a framework type the scan above cannot (and should not) discover — it stays the
     * one explicit, well-justified exception rather than a growing hand-maintained list.
     */
    @Bean
    JdbcTemplate jdbcTemplate() {
        return Mockito.mock(JdbcTemplate.class);
    }
}
