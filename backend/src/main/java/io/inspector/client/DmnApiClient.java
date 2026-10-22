package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.EngineApiContexts;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * DMN history reads against the {@code /dmn-api} sibling. A 404 or 401/403 means this REST app
 * does not expose DMN to the inspector account — never a fabricated empty decision list.
 */
@Component
public class DmnApiClient {

    private final GuardedCaller guarded;

    public DmnApiClient(GuardedCaller guarded) {
        this.guarded = guarded;
    }

    static String dmnApiBase(EngineConfig engine) {
        return EngineApiContexts.dmnApi(engine.baseUrl());
    }

    /** 200 = the context answers. Any 4xx = not exposed to this account. */
    public boolean probe(EngineConfig engine, CallPriority priority) {
        try {
            guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(UriComponentsBuilder.fromUriString(
                                            dmnApiBase(engine) + "/dmn-history/historic-decision-executions")
                                    .queryParam("size", 1)
                                    .build(true)
                                    .toUri())
                            .retrieve()
                            .toBodilessEntity());
            return true;
        } catch (HttpClientErrorException e) {
            return false;
        }
    }

    /** Null on 401/403/404 — the caller renders "no DMN engine". */
    public FlowablePage listByProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(UriComponentsBuilder.fromUriString(
                                            dmnApiBase(engine) + "/dmn-history/historic-decision-executions")
                                    .queryParam("processInstanceId", processInstanceId)
                                    .queryParam("size", size)
                                    .build(true)
                                    .toUri())
                            .retrieve()
                            .body(FlowablePage.class));
        } catch (HttpClientErrorException.NotFound
                | HttpClientErrorException.Forbidden
                | HttpClientErrorException.Unauthorized e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> auditData(EngineConfig engine, CallPriority priority, String executionId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(
                                    dmnApiBase(engine) + "/dmn-history/historic-decision-executions/{id}/auditdata",
                                    executionId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }
}
