package io.inspector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds the Engine Registry from application.yml (see docs/ARCHITECTURE.md §3).
 * Secrets are referenced by env-var NAME (passwordRef/tokenRef) and resolved at
 * client-build time — never stored here, never serialized to the UI.
 */
@ConfigurationProperties(prefix = "inspector")
public record InspectorProperties(
        Integer fanoutParallelism,
        List<EngineConfig> engines
) {

    public record EngineConfig(
            String id,
            String name,
            String baseUrl,
            String environment,
            String color,
            boolean enabled,
            Auth auth,
            Timeouts timeouts,
            Integer maxPageSize
    ) {
        public int maxPageSizeOrDefault() {
            return maxPageSize != null ? maxPageSize : 200;
        }
    }

    public record Auth(Type type, String username, String passwordRef, String tokenRef) {
        public enum Type { basic, bearer, none }
    }

    public record Timeouts(Integer connectMs, Integer readMs) {
        public int connect() { return connectMs != null ? connectMs : 2000; }
        public int read()    { return readMs != null ? readMs : 10000; }
    }
}
