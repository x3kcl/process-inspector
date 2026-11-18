package io.inspector.heat;

import com.github.benmanes.caffeine.cache.Ticker;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.HeatProperties;
import io.inspector.dto.DefinitionHeat;
import io.inspector.registry.EngineRegistry;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** RED stub (#784). */
@Service
public class DefinitionHeatService {

    public DefinitionHeatService(
            EngineRegistry registry,
            ProcessApiClient processes,
            CmmnApiClient cases,
            HeatProperties properties,
            Clock clock,
            Ticker ticker) {}

    public DefinitionHeat heat(String engineId, String definitionId, Integer n, boolean refresh) {
        throw new UnsupportedOperationException("RED stub #784");
    }
}
