package io.inspector.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties.EngineConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/** #629: the demo must be able to enable the shipped {@code engine-7} entry from its environment. */
class Engine7EnabledBindingTest {

    private static boolean engine7Enabled(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", env));
        EngineConfig e7 = Binder.get(environment).bind("inspector", InspectorProperties.class).get().engines().stream()
                .filter(e -> e.id().equals("engine-7"))
                .findFirst()
                .orElseThrow();
        return e7.enabled();
    }

    @Test
    void engine7_is_disabled_by_default() throws IOException {
        assertThat(engine7Enabled(Map.of())).isFalse();
    }

    @Test
    void engine7_is_enabled_by_INSPECTOR_ENGINE_7_ENABLED() throws IOException {
        assertThat(engine7Enabled(Map.of("INSPECTOR_ENGINE_7_ENABLED", "true"))).isTrue();
    }
}
