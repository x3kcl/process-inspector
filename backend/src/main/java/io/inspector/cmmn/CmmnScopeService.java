package io.inspector.cmmn;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.CmmnDeadLetterJob;
import io.inspector.dto.OutOfScopeDeadLetters;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Case Inspector Phase 1 (R-SEM-20): the drill-down behind the Stage-0
 * {@code outOfScopeDeadletters} count. Enumerates the CMMN dead-letter jobs on one engine by
 * paging the CMMN-api dead-letter list (the SIBLING of the process-api context) with a
 * server-side {@code ?scopeType=cmmn} filter (live-proven honored — the cap is spent on CMMN
 * rows only; docs/CMMN-SCOPE-PHASE-0.md §7). The intrinsic discriminator (non-null
 * {@code caseInstanceId}; §1.1 Q1) is retained as a defense-in-depth belt over that filter.
 *
 * <p>Doctrine held here:
 * <ul>
 *   <li><b>Gated 6.8+.</b> {@code scopeType} (Flowable ≥ 6.8) is the gate — 6.3.1's cmmn
 *       context is dead-letter-blind and would silently return a wrong view (spike Q3). Pre-6.8
 *       is REFUSED in the BFF with a ProblemDetail, never sent blind. The Phase-0 scalar is
 *       {@code null} on those engines, so the UI never drills in the first place; the server
 *       stays the gate regardless.</li>
 *   <li><b>Never an unpaged DLQ fetch</b> (iron rule). The scan is bounded by
 *       {@code dlq-scan-cap} and paged; a scan that hits the cap floors the result to a
 *       documented lower bound ({@code truncated}) — exactly like the BPMN Stage-0 scan.</li>
 *   <li><b>Read-only.</b> No move/discard — CMMN corrective actions are Phase 3.</li>
 * </ul>
 */
@Service
public class CmmnScopeService {

    private final EngineRegistry registry;
    private final FlowableEngineClient flowable;

    public CmmnScopeService(EngineRegistry registry, FlowableEngineClient flowable) {
        this.registry = registry;
        this.flowable = flowable;
    }

    /** The enumerated out-of-scope (CMMN) dead-letter jobs on one engine. */
    public OutOfScopeDeadLetters outOfScopeDeadLetters(String engineId) {
        EngineConfig engine = registry.require(engineId);
        requireScopeTypeCapability(engine);

        Map<String, String> filters = new LinkedHashMap<>();
        // Server-side scope filter (live-proven 2026-07-08): the cmmn-api DLQ list HONORS
        // ?scopeType=cmmn (narrowed an all-BPMN set 7→0), so the dlq-scan-cap is spent on CMMN
        // rows ONLY — BPMN jobs projected through this context never crowd CMMN past the cap,
        // and `truncated` below becomes a PURE-CMMN lower bound. (The process-api DLQ, by
        // contrast, ignores every scope param — hence the merge slice's degraded-orphan path.)
        filters.put("scopeType", "cmmn");
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }

        int cap = engine.dlqScanCapOrDefault();
        int pageSize = engine.maxPageSizeOrDefault();
        List<Map<String, Object>> cmmnRows = new ArrayList<>();
        int scanned = 0;
        long total = Long.MAX_VALUE;
        for (int start = 0; start < Math.min(total, cap); start += pageSize) {
            FlowablePage page =
                    flowable.listCmmnDeadLetterJobs(engine, filters, start, Math.min(pageSize, cap - start));
            if (page == null) {
                break;
            }
            total = page.total();
            if (page.dataOrEmpty().isEmpty()) {
                break;
            }
            for (Map<String, Object> row : page.dataOrEmpty()) {
                scanned++;
                if (isCmmnScoped(row)) {
                    cmmnRows.add(row);
                }
            }
        }

        // Resolve the bare-uuid caseDefinitionId to a readable key/name — DISTINCT ids only
        // (N+1 on definitions, never on jobs), each degrading to null so a resolution miss
        // (undeployed definition) never fails the slice.
        Map<String, CaseDefinitionRef> definitions = resolveDefinitions(engine, cmmnRows);
        List<CmmnDeadLetterJob> jobs = cmmnRows.stream()
                .map(row -> map(row, definitions.getOrDefault(str(row, "caseDefinitionId"), CaseDefinitionRef.UNKNOWN)))
                .toList();

        // The shared DLQ table (BPMN + CMMN rows) exceeded the cap: CMMN rows may lie past it,
        // so the enumerated list is a labeled lower bound (the UI's ≥N treatment).
        boolean truncated = total > scanned;
        return new OutOfScopeDeadLetters(jobs, truncated, scanned);
    }

    /** A resolved (or missing) case-definition key/name pair. */
    record CaseDefinitionRef(String key, String name) {
        static final CaseDefinitionRef UNKNOWN = new CaseDefinitionRef(null, null);
    }

    /**
     * Resolves each DISTINCT {@code caseDefinitionId} present in the rows to its key/name via
     * {@code cmmn-repository/case-definitions/{id}}. A per-id failure (undeployed definition →
     * 404 → null) degrades that one entry to {@link CaseDefinitionRef#UNKNOWN}; the scan never
     * fails on enrichment.
     */
    private Map<String, CaseDefinitionRef> resolveDefinitions(EngineConfig engine, List<Map<String, Object>> rows) {
        Map<String, CaseDefinitionRef> resolved = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String defId = str(row, "caseDefinitionId");
            if (defId == null || resolved.containsKey(defId)) {
                continue;
            }
            try {
                Map<String, Object> def = flowable.getCmmnCaseDefinition(engine, defId);
                resolved.put(
                        defId,
                        def == null
                                ? CaseDefinitionRef.UNKNOWN
                                : new CaseDefinitionRef(str(def, "key"), str(def, "name")));
            } catch (Exception ex) {
                resolved.put(defId, CaseDefinitionRef.UNKNOWN);
            }
        }
        return resolved;
    }

    /**
     * A CMMN-scoped row iff it carries a non-null {@code caseInstanceId}. With the server-side
     * {@code ?scopeType=cmmn} filter this is now defense-in-depth: were that filter ever dropped
     * by an engine, the intrinsic discriminator still keeps BPMN projections (null case
     * attribution; spike Q1) out of the drawer rather than leaking them.
     */
    static boolean isCmmnScoped(Map<String, Object> row) {
        Object caseInstanceId = row.get("caseInstanceId");
        return caseInstanceId != null && !caseInstanceId.toString().isBlank();
    }

    static CmmnDeadLetterJob map(Map<String, Object> row, CaseDefinitionRef definition) {
        Object retries = row.get("retries");
        return new CmmnDeadLetterJob(
                str(row, "id"),
                str(row, "caseInstanceId"),
                str(row, "caseDefinitionId"),
                definition.key(),
                definition.name(),
                str(row, "planItemInstanceId"),
                str(row, "elementId"),
                str(row, "elementName"),
                retries instanceof Number n ? n.intValue() : null,
                str(row, "exceptionMessage"),
                str(row, "createTime"),
                str(row, "dueDate"),
                str(row, "tenantId"));
    }

    private void requireScopeTypeCapability(EngineConfig engine) {
        var health = registry.healthOf(engine.id());
        EngineCapabilities capabilities = health != null ? health.capabilities() : null;
        if (capabilities == null) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unknown",
                    "Engine '" + engine.id() + "' has not answered a health probe yet — CMMN scope support"
                            + " (Flowable ≥ 6.8) is unverified, so the call is refused rather than sent blind.");
        }
        if (!capabilities.scopeType()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unavailable",
                    "Engine '" + engine.id() + "' runs an Unsupported Engine Version for CMMN scope"
                            + " (requires Flowable ≥ 6.8 — older engines are dead-letter-blind on the cmmn"
                            + " context) — refused in the BFF, never a silently wrong view.");
        }
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }
}
