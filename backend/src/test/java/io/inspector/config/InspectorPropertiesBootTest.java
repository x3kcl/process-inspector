package io.inspector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Boot-time fail-fast, same shape as {@link AuditPropertiesTest}: a bad value refuses startup and
 * the failure names the property path. Docker-free — no datasource, no full application.
 */
class InspectorPropertiesBootTest {

    @Test
    void negativeFanoutFailsStartupNamingTheProperty() {
        SpringApplication app = new SpringApplication(Boot.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> app.run(
                        "--spring.config.location=optional:classpath:/inspector-properties-boot-empty.yml",
                        "--inspector.fanout-parallelism=-1"))
                .satisfies(thrown -> assertThat(chain(thrown)).contains("fanoutParallelism"));
    }

    private static String chain(Throwable thrown) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
        }
        return String.join("\n", messages);
    }

    @Configuration
    @EnableConfigurationProperties(InspectorProperties.class)
    @ImportAutoConfiguration({
        ConfigurationPropertiesAutoConfiguration.class,
        ValidationAutoConfiguration.class
    })
    static class Boot {}
}
