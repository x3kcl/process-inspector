package io.inspector.surgery;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Fetches and caches the {@link BpmnStructure} of a deployed definition. A definition id
 * addresses immutable content (a redeploy mints a NEW id), so the parsed structure is
 * cached hard — the TTL only bounds memory on long-lived BFFs, not staleness.
 */
@Service
public class BpmnStructureService {

    private final ProcessApiClient client;
    private final Cache<String, BpmnStructure> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public BpmnStructureService(ProcessApiClient client) {
        this.client = client;
    }

    public BpmnStructure structureOf(EngineConfig engine, String processDefinitionId) {
        return cache.get(engine.id() + "|" + processDefinitionId, key -> load(engine, processDefinitionId));
    }

    private BpmnStructure load(EngineConfig engine, String processDefinitionId) {
        Map<String, Object> model =
                client.getProcessDefinitionModel(engine, CallPriority.INTERACTIVE, processDefinitionId);
        String xml = client.processDefinitionResourceData(engine, CallPriority.INTERACTIVE, processDefinitionId);
        if (model == null || xml == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "definition-not-found",
                    "Process definition " + processDefinitionId + " does not exist on '" + engine.id()
                            + "' — cannot validate the move against its model. Nothing happened.");
        }
        try {
            return BpmnStructure.parse(model, xml);
        } catch (IllegalStateException e) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "model-unparseable",
                    "The deployed BPMN of definition " + processDefinitionId + " could not be analyzed ("
                            + e.getMessage() + ") — flow surgery is refused without a verified model.");
        }
    }
}
