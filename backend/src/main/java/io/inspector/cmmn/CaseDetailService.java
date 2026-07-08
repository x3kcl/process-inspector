package io.inspector.cmmn;

import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.CaseDetail;
import io.inspector.dto.CaseDetail.CaseFailing;
import io.inspector.dto.CaseDiagram;
import io.inspector.dto.CasePlanItems;
import io.inspector.dto.CasePlanItems.CasePlanItem;
import io.inspector.dto.CmmnLiveJobState;
import io.inspector.registry.EngineRegistry;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Case Inspector Phase 2 (R-SEM-20): the read-only, polymorphic Stage-2 detail of ONE CMMN case —
 * the CMMN sibling of {@code InstanceDetailService}. Serves the {@code /case/{engineId}/
 * {caseInstanceId}} page: vitals (historic-first), the {@code cmmn-js} diagram (raw CMMN XML +
 * plan-item markers), and the plan-item state-machine timeline.
 *
 * <p>Doctrine held here (docs/CMMN-CASE-DETAIL-PHASE-2.md):
 * <ul>
 *   <li><b>Gated 6.8+</b> on every call via {@link CmmnCapabilities#requireScopeType} — 6.3.1's
 *       cmmn context is dead-letter-blind and stateless (spike Q3); refused with a ProblemDetail,
 *       never a silently wrong page.</li>
 *   <li><b>Runtime-only plan-item timeline</b> (spike Q6): {@code historic-plan-item-instances}
 *       404s on 6.8, so an ENDED case returns {@code available=false} + a reason — never a
 *       fabricated empty timeline.</li>
 *   <li><b>FAILED/RETRYING joined by {@code planItemInstanceId}</b> (spike Q7), NOT the job row's
 *       {@code elementId} (which is the plan-item DEFINITION id).</li>
 *   <li><b>Bounded + paged</b> (iron rule): plan-item and job scans page to a cap and flag
 *       {@code truncated}; no unpaged fetch.</li>
 *   <li><b>Read-only.</b> No mutation — CMMN corrective actions are Phase 3.</li>
 * </ul>
 */
@Service
public class CaseDetailService {

    /** Plan items per case are few; this bounds a pathological (deep-stage) case (iron rule). */
    static final int PLAN_ITEM_SCAN_CAP = 500;

    private final EngineRegistry registry;
    private final FlowableEngineClient flowable;

    public CaseDetailService(EngineRegistry registry, FlowableEngineClient flowable) {
        this.registry = registry;
        this.flowable = flowable;
    }

    /* ============================ vitals ============================ */

    /** Case vitals, historic-first with a runtime overlay for live state. Gated 6.8+. */
    public CaseDetail vitals(String engineId, String caseInstanceId) {
        EngineConfig engine = requireGatedEngine(engineId);

        Map<String, Object> historic = flowable.getHistoricCmmnCaseInstance(engine, caseInstanceId);
        Map<String, Object> runtime = flowable.getCmmnCaseInstance(engine, caseInstanceId);
        Map<String, Object> base = historic != null ? historic : runtime;
        if (base == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "CMMN case " + caseInstanceId + " not found on " + engineId);
        }

        boolean ended = historic != null && str(historic, "endTime") != null && runtime == null;
        String state = normalizeState(runtime, historic, ended);

        String caseDefinitionId = str(base, "caseDefinitionId");
        CaseDefinitionRef def = resolveDefinition(engine, caseDefinitionId);

        CaseFailing failing = failingSummary(engine, caseInstanceId);

        return new CaseDetail(
                engineId,
                caseInstanceId,
                str(base, "businessKey"),
                state,
                caseDefinitionId,
                def.key(),
                def.name(),
                def.version(),
                str(base, "startTime"),
                str(historic, "endTime"),
                str(base, "startUserId"),
                str(base, "superProcessInstanceId"),
                str(base, "parentId"),
                str(base, "tenantId"),
                true,
                ended,
                failing);
    }

    /**
     * ACTIVE / COMPLETED / TERMINATED (never SUSPENDED — cases can't suspend, spike Q2). A live
     * runtime case is ACTIVE; otherwise the historic {@code state} (6.8 carries it) distinguishes
     * COMPLETED from TERMINATED; a missing state falls back to endTime presence.
     */
    static String normalizeState(Map<String, Object> runtime, Map<String, Object> historic, boolean ended) {
        if (runtime != null && !ended) {
            return "ACTIVE";
        }
        String s = str(historic, "state");
        if (s != null && !s.isBlank()) {
            return s.toUpperCase(Locale.ROOT);
        }
        return ended ? "COMPLETED" : "ACTIVE";
    }

    /** The "why stuck" summary — present only when the case carries a dead-lettered plan item. */
    private CaseFailing failingSummary(EngineConfig engine, String caseInstanceId) {
        FlowablePage page = flowable.listCmmnDeadLetterJobs(engine, caseFilters(engine, caseInstanceId), 0, 20);
        if (page == null || page.total() == 0) {
            return null;
        }
        List<Map<String, Object>> rows = page.dataOrEmpty();
        Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
        return new CaseFailing((int) page.total(), str(first, "exceptionMessage"), str(first, "elementName"));
    }

    /* ============================ diagram ============================ */

    /** The raw CMMN XML for {@code cmmn-js} + plan-item marker id sets. Gated 6.8+. */
    public CaseDiagram diagram(String engineId, String caseInstanceId) {
        EngineConfig engine = requireGatedEngine(engineId);

        Map<String, Object> base = requireCase(engine, engineId, caseInstanceId);
        String caseDefinitionId = str(base, "caseDefinitionId");
        Map<String, Object> definition =
                caseDefinitionId == null ? null : flowable.getCmmnCaseDefinition(engine, caseDefinitionId);
        if (definition == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "CMMN case definition " + caseDefinitionId + " not found on " + engineId);
        }
        String deploymentId = str(definition, "deploymentId");
        String resourceName = resourceNameOf(definition);
        String xml = deploymentId == null || resourceName == null
                ? null
                : flowable.cmmnDeploymentResourceData(engine, deploymentId, resourceName);
        if (xml == null || xml.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "CMMN resource for definition " + caseDefinitionId + " not found on " + engineId);
        }
        boolean graphicalNotationDefined = Boolean.TRUE.equals(definition.get("graphicalNotationDefined"));

        // Markers come from the RUNTIME plan items joined to the case's jobs (empty for an ended
        // case — the static model still renders). Keyed by the plan item's elementId (the CMMN DI
        // shape key), NOT a job's elementId (the definition id; spike Q7).
        List<CasePlanItem> planItems = scanPlanItems(engine, caseInstanceId).planItems();
        List<String> active = planItems.stream()
                .filter(p -> isActiveState(p.state()))
                .map(CasePlanItem::elementId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        List<String> failed = planItems.stream()
                .filter(p -> p.liveJobState() == CmmnLiveJobState.FAILED)
                .map(CasePlanItem::elementId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return new CaseDiagram(xml, graphicalNotationDefined, active, failed);
    }

    /** A plan item that currently holds the case's attention — highlighted on the canvas. */
    static boolean isActiveState(String state) {
        if (state == null) {
            return false;
        }
        return switch (state) {
            case "active", "async-active", "enabled", "available" -> true;
            default -> false;
        };
    }

    /* ========================== plan items ========================== */

    /** The plan-item state-machine timeline. Runtime-only (spike Q6): ended cases carry a reason. */
    public CasePlanItems planItems(String engineId, String caseInstanceId) {
        EngineConfig engine = requireGatedEngine(engineId);
        Map<String, Object> historic = flowable.getHistoricCmmnCaseInstance(engine, caseInstanceId);
        Map<String, Object> runtime = flowable.getCmmnCaseInstance(engine, caseInstanceId);
        if (historic == null && runtime == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "CMMN case " + caseInstanceId + " not found on " + engineId);
        }
        boolean ended = runtime == null && historic != null && str(historic, "endTime") != null;
        if (ended) {
            return new CasePlanItems(
                    false,
                    "The plan-item timeline is available for running cases only on this engine"
                            + " (Flowable 6.8 exposes no historic plan-item REST API). The case has ended;"
                            + " its vitals remain from case history.",
                    false,
                    List.of());
        }
        return scanPlanItems(engine, caseInstanceId);
    }

    /**
     * Pages the runtime plan items (bounded by {@link #PLAN_ITEM_SCAN_CAP}) and joins each to the
     * case's jobs by {@code planItemInstanceId} (spike Q7) for its {@code liveJobState}.
     */
    private CasePlanItems scanPlanItems(EngineConfig engine, String caseInstanceId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int pageSize = engine.maxPageSizeOrDefault();
        long total = Long.MAX_VALUE;
        boolean truncated = false;
        for (int start = 0; start < Math.min(total, PLAN_ITEM_SCAN_CAP); start += pageSize) {
            int want = Math.min(pageSize, PLAN_ITEM_SCAN_CAP - start);
            FlowablePage page =
                    flowable.listCmmnPlanItemInstances(engine, caseFilters(engine, caseInstanceId), start, want);
            if (page == null) {
                break;
            }
            total = page.total();
            List<Map<String, Object>> data = page.dataOrEmpty();
            if (data.isEmpty()) {
                break;
            }
            rows.addAll(data);
            if (rows.size() >= total) {
                break;
            }
        }
        truncated = total > rows.size();

        Map<String, CmmnLiveJobState> jobStates = liveJobStates(engine, caseInstanceId);
        List<CasePlanItem> planItems = rows.stream()
                .map(row -> mapPlanItem(row, jobStates.get(str(row, "id"))))
                .toList();
        return new CasePlanItems(true, null, truncated, planItems);
    }

    /**
     * A {@code planItemInstanceId → liveJobState} map for one case: FAILED (dead-letter, retries
     * exhausted) takes precedence over RETRYING (an executable job with retries left). Keyed by
     * the JOB row's {@code planItemInstanceId} (== the plan item's {@code id}), never its
     * {@code elementId} (the definition id — spike Q7).
     */
    Map<String, CmmnLiveJobState> liveJobStates(EngineConfig engine, String caseInstanceId) {
        Map<String, CmmnLiveJobState> byPlanItem = new LinkedHashMap<>();
        // RETRYING first, so a later FAILED entry for the same plan item overwrites it (precedence).
        FlowablePage retrying =
                flowable.listCmmnJobs(engine, caseFilters(engine, caseInstanceId), 0, PLAN_ITEM_SCAN_CAP);
        putJobStates(byPlanItem, retrying, CmmnLiveJobState.RETRYING);
        FlowablePage failed =
                flowable.listCmmnDeadLetterJobs(engine, caseFilters(engine, caseInstanceId), 0, PLAN_ITEM_SCAN_CAP);
        putJobStates(byPlanItem, failed, CmmnLiveJobState.FAILED);
        return byPlanItem;
    }

    private static void putJobStates(
            Map<String, CmmnLiveJobState> byPlanItem, FlowablePage page, CmmnLiveJobState state) {
        if (page == null) {
            return;
        }
        for (Map<String, Object> job : page.dataOrEmpty()) {
            String planItemInstanceId = str(job, "planItemInstanceId");
            if (planItemInstanceId != null && !planItemInstanceId.isBlank()) {
                byPlanItem.put(planItemInstanceId, state);
            }
        }
    }

    /** Maps one runtime plan-item row to the DTO, carrying its joined {@code liveJobState}. */
    static CasePlanItem mapPlanItem(Map<String, Object> row, CmmnLiveJobState liveJobState) {
        return new CasePlanItem(
                str(row, "id"),
                str(row, "elementId"),
                str(row, "name"),
                str(row, "planItemDefinitionType"),
                str(row, "state"),
                Boolean.TRUE.equals(row.get("stage")),
                str(row, "stageInstanceId"),
                str(row, "createTime"),
                str(row, "lastAvailableTime"),
                str(row, "lastEnabledTime"),
                str(row, "lastStartedTime"),
                str(row, "completedTime"),
                str(row, "occurredTime"),
                str(row, "terminatedTime"),
                str(row, "exitTime"),
                str(row, "endedTime"),
                liveJobState);
    }

    /* ============================ helpers ============================ */

    private EngineConfig requireGatedEngine(String engineId) {
        EngineConfig engine = registry.require(engineId);
        CmmnCapabilities.requireScopeType(registry, engine);
        return engine;
    }

    private Map<String, Object> requireCase(EngineConfig engine, String engineId, String caseInstanceId) {
        Map<String, Object> historic = flowable.getHistoricCmmnCaseInstance(engine, caseInstanceId);
        Map<String, Object> base = historic != null ? historic : flowable.getCmmnCaseInstance(engine, caseInstanceId);
        if (base == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "CMMN case " + caseInstanceId + " not found on " + engineId);
        }
        return base;
    }

    /** The scope-and-case filter every case-scoped CMMN list uses (+ tenant thread when set). */
    private static Map<String, String> caseFilters(EngineConfig engine, String caseInstanceId) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("scopeType", "cmmn");
        filters.put("caseInstanceId", caseInstanceId);
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }
        return filters;
    }

    private CaseDefinitionRef resolveDefinition(EngineConfig engine, String caseDefinitionId) {
        if (caseDefinitionId == null) {
            return CaseDefinitionRef.UNKNOWN;
        }
        try {
            Map<String, Object> def = flowable.getCmmnCaseDefinition(engine, caseDefinitionId);
            if (def == null) {
                return CaseDefinitionRef.UNKNOWN;
            }
            Object version = def.get("version");
            return new CaseDefinitionRef(
                    str(def, "key"), str(def, "name"), version instanceof Number n ? n.intValue() : null);
        } catch (Exception ex) {
            return CaseDefinitionRef.UNKNOWN;
        }
    }

    /** A resolved (or missing) case-definition identity. */
    record CaseDefinitionRef(String key, String name, Integer version) {
        static final CaseDefinitionRef UNKNOWN = new CaseDefinitionRef(null, null, null);
    }

    /**
     * The deployment resource name of a case definition — 6.x serializes it only as the
     * {@code resource} URL ({@code …/deployments/{id}/resources/{name}}); the name is the decoded
     * tail. A plain {@code resourceName} field (if a newer engine adds one) wins.
     */
    static String resourceNameOf(Map<String, Object> definition) {
        String plain = str(definition, "resourceName");
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        String url = str(definition, "resource");
        if (url == null) {
            return null;
        }
        int at = url.indexOf("/resources/");
        if (at < 0) {
            return null;
        }
        return URLDecoder.decode(url.substring(at + "/resources/".length()), StandardCharsets.UTF_8);
    }

    private static String str(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }
}
