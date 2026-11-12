package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reads for case migration at scale (#500): the cohort list, the per-version counts and the
 * engine's case-migration batches. Wire facts measured on flowable-rest 6.8.0 and 7.1.0
 * (INSTANCE-MIGRATION.md §7.3). The one mutation stays {@link CmmnApiClient#migrateCase}.
 * Callers capability-gate ({@code scopeType}, ≥ 6.8) first.
 */
@Component
public class CaseMigrationClient {

    /** The {@code batchType} a 7.x engine gives a case-migration batch. */
    public static final String BATCH_TYPE = "caseMigration";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> ROWS =
            new ParameterizedTypeReference<>() {};

    private final GuardedCaller guarded;

    public CaseMigrationClient(GuardedCaller guarded) {
        this.guarded = guarded;
    }

    /**
     * Running cases on one case definition, in id order so two reads of an unchanged cohort
     * agree. {@code businessKeyLike} is honored on 7.x only: 6.8 ignores the parameter and
     * returns the unfiltered set, so the caller must not send it there.
     */
    public FlowablePage listRunningCases(
            EngineConfig engine,
            CallPriority priority,
            String caseDefinitionId,
            String businessKey,
            String businessKeyLike,
            String startedAfter,
            String startedBefore,
            int size) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(
                        CmmnApiClient.cmmnApiBase(engine) + "/cmmn-runtime/case-instances")
                .queryParam("caseDefinitionId", "{caseDefinitionId}")
                .queryParam("sort", "id")
                .queryParam("order", "asc")
                .queryParam("size", size);
        add(b, "businessKey", businessKey);
        add(b, "businessKeyLike", businessKeyLike);
        add(b, "startedAfter", startedAfter);
        add(b, "startedBefore", startedBefore);
        java.net.URI uri = b.encode()
                .buildAndExpand(vars(caseDefinitionId, businessKey, businessKeyLike, startedAfter, startedBefore))
                .toUri();
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
    }

    /** Count-only ended cases on one case definition ({@code finished=true}, {@code size=1}). */
    public long countEndedCases(EngineConfig engine, CallPriority priority, String caseDefinitionId) {
        java.net.URI uri = UriComponentsBuilder.fromUriString(
                        CmmnApiClient.cmmnApiBase(engine) + "/cmmn-history/historic-case-instances")
                .queryParam("caseDefinitionId", "{caseDefinitionId}")
                .queryParam("finished", true)
                .queryParam("size", 1)
                .encode()
                .buildAndExpand(Map.of("caseDefinitionId", caseDefinitionId))
                .toUri();
        FlowablePage page = guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
        return page != null ? page.total() : 0;
    }

    /** Deployed versions of one case key, newest first. */
    public FlowablePage listCaseDefinitionVersionsDesc(
            EngineConfig engine, CallPriority priority, String key, int size) {
        java.net.URI uri = UriComponentsBuilder.fromUriString(
                        CmmnApiClient.cmmnApiBase(engine) + "/cmmn-repository/case-definitions")
                .queryParam("key", "{key}")
                .queryParam("sort", "version")
                .queryParam("order", "desc")
                .queryParam("size", size)
                .encode()
                .buildAndExpand(Map.of("key", key))
                .toUri();
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
    }

    /**
     * Case-migration batches whose source is this case definition, in the engine's id order
     * (the list ignores {@code sort}). The CMMN API has no batch route; the batch rows live
     * behind the process API's {@code management/batches} ({@code searchKey} = source definition
     * id, {@code searchKey2} = target — measured on 7.1.0).
     */
    public FlowablePage listCaseMigrationBatches(
            EngineConfig engine, CallPriority priority, String sourceCaseDefinitionId, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(
                                "/management/batches?batchType={type}&searchKey={source}&size={size}",
                                BATCH_TYPE,
                                sourceCaseDefinitionId,
                                size)
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** One batch's parts: a bare JSON array, one row per case ({@code scopeId}, {@code status}). */
    public List<Map<String, Object>> listBatchParts(EngineConfig engine, CallPriority priority, String batchId) {
        String id = GuardedCaller.safeId(batchId);
        List<Map<String, Object>> rows = guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri("/management/batches/{id}/batch-parts", id)
                        .retrieve()
                        .body(ROWS));
        return rows == null ? List.of() : rows;
    }

    private static void add(UriComponentsBuilder b, String name, String value) {
        if (value != null && !value.isBlank()) {
            b.queryParam(name, "{" + name + "}");
        }
    }

    private static Map<String, Object> vars(
            String caseDefinitionId,
            String businessKey,
            String businessKeyLike,
            String startedAfter,
            String startedBefore) {
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("caseDefinitionId", caseDefinitionId);
        vars.put("businessKey", businessKey);
        vars.put("businessKeyLike", businessKeyLike);
        vars.put("startedAfter", startedAfter);
        vars.put("startedBefore", startedBefore);
        return vars;
    }
}
