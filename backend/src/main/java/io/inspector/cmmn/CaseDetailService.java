package io.inspector.cmmn;

import io.inspector.audit.VariableValueToken;
import io.inspector.client.CaseDefinitionRecord;
import io.inspector.client.CaseIdentityLinkRecord;
import io.inspector.client.CaseInstanceRecord;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.CmmnJobPage;
import io.inspector.client.CmmnJobRecord;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.PlanItemPage;
import io.inspector.client.PlanItemRecord;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.CaseDetail;
import io.inspector.dto.CaseDetail.CaseFailing;
import io.inspector.dto.CaseDiagram;
import io.inspector.dto.CasePlanItems;
import io.inspector.dto.CasePlanItems.CasePlanItem;
import io.inspector.dto.CmmnLiveJobState;
import io.inspector.dto.InstanceVariables.VariableDto;
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
    private final CmmnApiClient flowable;

    public CaseDetailService(EngineRegistry registry, CmmnApiClient flowable) {
        this.registry = registry;
        this.flowable = flowable;
    }

    /* ============================ vitals ============================ */

    /** Case vitals, historic-first with a runtime overlay for live state. Gated 6.8+. */
    public CaseDetail vitals(String engineId, String caseInstanceId) {
        EngineConfig engine = requireGatedEngine(engineId);

        CaseInstanceRecord historic =
                flowable.getHistoricCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseInstanceId);
        CaseInstanceRecord runtime = flowable.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseInstanceId);
        CaseInstanceRecord base = historic != null ? historic : runtime;
        if (base == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "CMMN case " + caseInstanceId + " not found on " + engineId);
        }

        boolean ended = historic != null && historic.endTime() != null && runtime == null;
        String state = normalizeState(runtime, historic, ended);

        String caseDefinitionId = base.caseDefinitionId();
        CaseDefinitionRef def = resolveDefinition(engine, caseDefinitionId);

        CaseFailing failing = failingSummary(engine, caseInstanceId);

        return new CaseDetail(
                engineId,
                caseInstanceId,
                base.businessKey(),
                state,
                caseDefinitionId,
                def.key(),
                def.name(),
                def.version(),
                base.startTime(),
                historic != null ? historic.endTime() : null,
                base.startUserId(),
                base.superProcessInstanceId(),
                base.parentId(),
                base.tenantId(),
                true,
                ended,
                failing);
    }

    /**
     * ACTIVE / COMPLETED / TERMINATED (never SUSPENDED — cases can't suspend, spike Q2). A live
     * runtime case is ACTIVE; otherwise the historic {@code state} (6.8 carries it) distinguishes
     * COMPLETED from TERMINATED; a missing state falls back to endTime presence.
     */
    static String normalizeState(CaseInstanceRecord runtime, CaseInstanceRecord historic, boolean ended) {
        if (runtime != null && !ended) {
            return "ACTIVE";
        }
        String s = historic != null ? historic.state() : null;
        if (s != null && !s.isBlank()) {
            return s.toUpperCase(Locale.ROOT);
        }
        return ended ? "COMPLETED" : "ACTIVE";
    }

    /** The "why stuck" summary — present only when the case carries a dead-lettered plan item. */
    private CaseFailing failingSummary(EngineConfig engine, String caseInstanceId) {
        CmmnJobPage page = flowable.listCmmnDeadLetterJobs(
                engine, CallPriority.INTERACTIVE, caseFilters(engine, caseInstanceId), 0, 20);
        if (page == null || page.total() == 0) {
            return null;
        }
        List<CmmnJobRecord> rows = page.dataOrEmpty();
        CmmnJobRecord first = rows.isEmpty() ? null : rows.get(0);
        List<CaseFailing.DeadLetterJobRef> jobs = rows.stream()
                .map(r -> new CaseFailing.DeadLetterJobRef(r.id(), r.elementName(), r.exceptionMessage(), r.retries()))
                .toList();
        return new CaseFailing(
                (int) page.total(),
                first != null ? first.exceptionMessage() : null,
                first != null ? first.elementName() : null,
                jobs);
    }

    /* ============================ diagram ============================ */

    /** The raw CMMN XML for {@code cmmn-js} + plan-item marker id sets. Gated 6.8+. */
    public CaseDiagram diagram(String engineId, String caseInstanceId) {
        EngineConfig engine = requireGatedEngine(engineId);

        CaseInstanceRecord base = requireCase(engine, engineId, caseInstanceId);
        String caseDefinitionId = base.caseDefinitionId();
        CaseDefinitionRecord definition = caseDefinitionId == null
                ? null
                : flowable.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, caseDefinitionId);
        if (definition == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "CMMN case definition " + caseDefinitionId + " not found on " + engineId);
        }
        String deploymentId = definition.deploymentId();
        String resourceName = resourceNameOf(definition);
        String xml = deploymentId == null || resourceName == null
                ? null
                : flowable.cmmnDeploymentResourceData(engine, CallPriority.INTERACTIVE, deploymentId, resourceName);
        if (xml == null || xml.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "CMMN resource for definition " + caseDefinitionId + " not found on " + engineId);
        }
        boolean graphicalNotationDefined = Boolean.TRUE.equals(definition.graphicalNotationDefined());

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
        CaseInstanceRecord historic =
                flowable.getHistoricCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseInstanceId);
        CaseInstanceRecord runtime = flowable.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseInstanceId);
        if (historic == null && runtime == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "CMMN case " + caseInstanceId + " not found on " + engineId);
        }
        boolean ended = runtime == null && historic != null && historic.endTime() != null;
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
        List<PlanItemRecord> rows = new ArrayList<>();
        int pageSize = engine.maxPageSizeOrDefault();
        long total = Long.MAX_VALUE;
        boolean truncated = false;
        for (int start = 0; start < Math.min(total, PLAN_ITEM_SCAN_CAP); start += pageSize) {
            int want = Math.min(pageSize, PLAN_ITEM_SCAN_CAP - start);
            PlanItemPage page = flowable.listCmmnPlanItemInstances(
                    engine, CallPriority.INTERACTIVE, caseFilters(engine, caseInstanceId), start, want);
            if (page == null) {
                break;
            }
            total = page.total();
            List<PlanItemRecord> data = page.dataOrEmpty();
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
                .map(row -> mapPlanItem(row, jobStates.get(row.id())))
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
        CmmnJobPage retrying = flowable.listCmmnJobs(
                engine, CallPriority.INTERACTIVE, caseFilters(engine, caseInstanceId), 0, PLAN_ITEM_SCAN_CAP);
        putJobStates(byPlanItem, retrying, CmmnLiveJobState.RETRYING);
        CmmnJobPage failed = flowable.listCmmnDeadLetterJobs(
                engine, CallPriority.INTERACTIVE, caseFilters(engine, caseInstanceId), 0, PLAN_ITEM_SCAN_CAP);
        putJobStates(byPlanItem, failed, CmmnLiveJobState.FAILED);
        return byPlanItem;
    }

    private static void putJobStates(
            Map<String, CmmnLiveJobState> byPlanItem, CmmnJobPage page, CmmnLiveJobState state) {
        if (page == null) {
            return;
        }
        for (CmmnJobRecord job : page.dataOrEmpty()) {
            String planItemInstanceId = job.planItemInstanceId();
            if (planItemInstanceId != null && !planItemInstanceId.isBlank()) {
                byPlanItem.put(planItemInstanceId, state);
            }
        }
    }

    /** Maps one runtime plan-item row to the DTO, carrying its joined {@code liveJobState}. */
    static CasePlanItem mapPlanItem(PlanItemRecord row, CmmnLiveJobState liveJobState) {
        return new CasePlanItem(
                row.id(),
                row.elementId(),
                row.name(),
                row.planItemDefinitionType(),
                row.state(),
                Boolean.TRUE.equals(row.stage()),
                row.stageInstanceId(),
                row.createTime(),
                row.lastAvailableTime(),
                row.lastEnabledTime(),
                row.lastStartedTime(),
                row.completedTime(),
                row.occurredTime(),
                row.terminatedTime(),
                row.exitTime(),
                row.endedTime(),
                liveJobState);
    }

    /* ============================ helpers ============================ */

    private EngineConfig requireGatedEngine(String engineId) {
        EngineConfig engine = registry.resolveOrNotFound(engineId);
        CmmnCapabilities.requireScopeType(registry, engine);
        return engine;
    }

    private CaseInstanceRecord requireCase(EngineConfig engine, String engineId, String caseInstanceId) {
        CaseInstanceRecord historic =
                flowable.getHistoricCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseInstanceId);
        CaseInstanceRecord base = historic != null
                ? historic
                : flowable.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseInstanceId);
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
            CaseDefinitionRecord def =
                    flowable.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, caseDefinitionId);
            if (def == null) {
                return CaseDefinitionRef.UNKNOWN;
            }
            return new CaseDefinitionRef(def.key(), def.name(), def.version());
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
    static String resourceNameOf(CaseDefinitionRecord definition) {
        String plain = definition.resourceName();
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        String url = definition.resource();
        if (url == null) {
            return null;
        }
        int at = url.indexOf("/resources/");
        if (at < 0) {
            return null;
        }
        return URLDecoder.decode(url.substring(at + "/resources/".length()), StandardCharsets.UTF_8);
    }

    /**
     * Case variables through the masking choke point ({@link VariableDto}, #742): a secret-named
     * row ships masked with an opaque CAS token (the case editor has no single-value read).
     */
    public List<VariableDto> variables(String engineId, String caseInstanceId) {
        EngineConfig engine = registry.require(engineId);
        CmmnCapabilities.requireScopeType(registry, engine);
        return flowable.listCaseVariables(engine, CallPriority.INTERACTIVE, caseInstanceId).stream()
                .map(row -> new VariableDto(
                        row.name(),
                        row.type(),
                        row.value(),
                        false,
                        null,
                        row.scope(),
                        null,
                        null,
                        false,
                        VariableValueToken.issue(engine.id(), caseInstanceId, row.name(), row.value())))
                .toList();
    }

    public List<CaseIdentityLinkRecord> identityLinks(String engineId, String caseInstanceId) {
        EngineConfig engine = registry.require(engineId);
        CmmnCapabilities.requireScopeType(registry, engine);
        return flowable.listCaseIdentityLinks(engine, CallPriority.INTERACTIVE, caseInstanceId);
    }
}
