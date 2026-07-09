package io.inspector.config;

import io.inspector.registry.RegistryUrlValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the S1 {@link RegistryUrlValidator} as a bean (system DNS resolver) so the S4 admin write
 * path can SSRF-validate a base-URL at the door before persisting it (docs/REGISTRY-CRUD.md §5).
 * The egress boundary it validates against comes from {@link RegistryProperties#egressPolicy()}.
 */
@Configuration
public class RegistryValidationConfig {

    @Bean
    RegistryUrlValidator registryUrlValidator() {
        return new RegistryUrlValidator();
    }
}
