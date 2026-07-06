package io.inspector.registry;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-memory view of the configured engines plus their live health state.
 * Health is written by {@link EngineHealthService} and read by the API layer.
 */
@Component
public class EngineRegistry {

    private final Map<String, EngineConfig> engines;
    private final Map<String, EngineHealth> health = new ConcurrentHashMap<>();

    public EngineRegistry(InspectorProperties props) {
        // LinkedHashMap: the health strip and every fan-out follow registry YAML order.
        this.engines = props.engines().stream()
                .filter(EngineConfig::enabled)
                .collect(Collectors.toMap(EngineConfig::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        engines.keySet().forEach(id -> health.put(id, EngineHealth.unknown()));
    }

    public List<EngineConfig> all() {
        return List.copyOf(engines.values());
    }

    public EngineConfig require(String engineId) {
        EngineConfig cfg = engines.get(engineId);
        if (cfg == null) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown engine: " + engineId);
        }
        return cfg;
    }

    public EngineHealth healthOf(String engineId) {
        return health.getOrDefault(engineId, EngineHealth.unknown());
    }

    public void updateHealth(String engineId, EngineHealth h) {
        health.put(engineId, h);
    }
}
