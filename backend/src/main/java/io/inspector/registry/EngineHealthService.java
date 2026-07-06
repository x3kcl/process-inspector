package io.inspector.registry;

import io.inspector.client.FlowableEngineClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry.EngineHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Probes GET /management/engine on every registered engine — on boot and on a
 * fixed schedule — so /api/engines can show live reachability + version without
 * paying a probe on every UI request.
 */
@Service
public class EngineHealthService {

    private static final Logger log = LoggerFactory.getLogger(EngineHealthService.class);

    private final EngineRegistry registry;
    private final FlowableEngineClient flowable;

    public EngineHealthService(EngineRegistry registry, FlowableEngineClient flowable) {
        this.registry = registry;
        this.flowable = flowable;
    }

    @Scheduled(initialDelay = 2_000, fixedDelay = 30_000)
    public void probeAll() {
        for (EngineConfig engine : registry.all()) {
            try {
                Map<String, Object> info = flowable.engineInfo(engine);
                String version = info.get("version") != null ? info.get("version").toString() : "unknown";
                registry.updateHealth(engine.id(),
                        new EngineHealth(true, version, null, System.currentTimeMillis()));
            } catch (Exception ex) {
                log.debug("Health probe failed for {}: {}", engine.id(), ex.toString());
                registry.updateHealth(engine.id(),
                        new EngineHealth(false, null, ex.getMessage(), System.currentTimeMillis()));
            }
        }
    }
}
