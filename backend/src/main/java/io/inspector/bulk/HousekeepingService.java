package io.inspector.bulk;

import io.inspector.action.ActionVerb;
import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedDefinition;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Delete completed engine history (#488). Resolution is server-side and re-run at submit.
 * Per-id DELETE, not the id-list bulk route: that route has no per-item body.
 */
@Service
public class HousekeepingService {

    private static final int PAGE = 50;

    private final ProcessApiClient flowable;
    private final CmmnApiClient cmmn;
    private final BulkJobService bulk;
    private final EngineRegistry registry;
    private final RbacAuthorizer rbac;
    private final ProtectedDefinitionRepository protectedDefinitions;
    private final AuditService audit;
    private final List<String> heldKeys;
    private final List<String> heldBusinessKeys;

    public HousekeepingService(
            ProcessApiClient flowable,
            CmmnApiClient cmmn,
            BulkJobService bulk,
            EngineRegistry registry,
            RbacAuthorizer rbac,
            ProtectedDefinitionRepository protectedDefinitions,
            AuditService audit,
            @Value("${inspector.housekeeping.held-definition-keys:}") String heldKeys,
            @Value("${inspector.housekeeping.held-business-keys:}") String heldBusinessKeys) {
        this.flowable = flowable;
        this.cmmn = cmmn;
        this.bulk = bulk;
        this.registry = registry;
        this.rbac = rbac;
        this.protectedDefinitions = protectedDefinitions;
        this.audit = audit;
        this.heldKeys = split(heldKeys);
        this.heldBusinessKeys = split(heldBusinessKeys);
    }

    public BulkDtos.HistoryPreview preview(BulkDtos.HistoryDeleteRequest request, Authentication auth) {
        Resolved resolved = resolve(request, auth);
        List<String> sample = resolved.hits().stream().limit(20).map(Hit::id).toList();
        return new BulkDtos.HistoryPreview(resolved.hits().size(), resolved.capped(), resolved.prod(), sample);
    }

    public BulkDtos.BulkJobDto submit(BulkDtos.HistoryDeleteRequest request, Authentication auth) {
        if (request.reason() == null
                || request.reason().isBlank()
                || request.reason().trim().length() < 10) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        Resolved resolved = resolve(request, auth);
        if (resolved.hits().isEmpty()) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.CONFLICT, "filter-drained", "No finished instances match. Nothing happened.");
        }
        if (resolved.capped()) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST,
                    "history-capped",
                    "More than " + BulkJob.FILTER_ITEM_CAP
                            + " instances match. Narrow the definition or the end date. Nothing happened.");
        }
        if (resolved.prod()) {
            if (request.confirmedCount() == null) {
                throw BulkFilterResolution.refuse(
                        HttpStatus.BAD_REQUEST,
                        "bulk-destructive-confirm-count-required",
                        "This scope includes a PRODUCTION engine — type the count ("
                                + resolved.hits().size() + "). Nothing happened.");
            }
            if (request.confirmedCount() != resolved.hits().size()) {
                throw new BulkCountDriftException(
                        request.confirmedCount(), resolved.hits().size());
            }
        }
        ActionVerb verb = resolved.cases() ? ActionVerb.DELETE_CASE_HISTORY : ActionVerb.DELETE_HISTORY;
        List<BulkDtos.BulkTarget> items = resolved.hits().stream()
                .map(hit -> new BulkDtos.BulkTarget(hit.engineId(), hit.id(), null))
                .toList();
        return bulk.submitHousekeeping(
                new BulkDtos.BulkSubmitRequest(verb.path(), request.reason().trim(), request.ticketId(), null, items),
                auth,
                Map.of(
                        "finishedBefore",
                        request.finishedBefore(),
                        "definitionKey",
                        String.valueOf(request.definitionKey())),
                "history " + request.definitionKey() + " before " + request.finishedBefore());
    }

    private Resolved resolve(BulkDtos.HistoryDeleteRequest request, Authentication auth) {
        Instant finishedBefore = parseBefore(request.finishedBefore());
        boolean cases = "cmmn".equalsIgnoreCase(request.scope());
        List<EngineConfig> engines = engines(request.engineIds());
        boolean prod = engines.stream().anyMatch(engine -> engine.environment() == EngineEnvironment.PROD);
        if (prod && (request.definitionKey() == null || request.definitionKey().isBlank())) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST,
                    "history-key-required",
                    "A definition key is required when a PRODUCTION engine is in scope. Nothing happened.");
        }
        for (EngineConfig engine : engines) {
            if (!rbac.hasRoleOn(auth, Role.ADMIN, engine.id())) {
                throw BulkFilterResolution.refuse(
                        HttpStatus.FORBIDDEN,
                        "bulk-destructive-rbac-denied",
                        "ADMIN on engine '" + engine.id() + "' is required. Nothing happened.");
            }
        }
        String key = blank(request.definitionKey());
        if (key != null && heldKeys.contains(key)) {
            refuseHeld(auth, key, null);
        }
        if (key != null) {
            for (EngineConfig engine : engines) {
                if (protectedDefinitions.existsById(new ProtectedDefinition.Key(engine.id(), key))) {
                    throw BulkFilterResolution.refuse(
                            HttpStatus.CONFLICT,
                            "definition-protected",
                            "Definition '" + key + "' is protected on '" + engine.id() + "'. Nothing happened.");
                }
            }
        }
        List<Hit> hits = new ArrayList<>();
        boolean capped = false;
        String before = finishedBefore
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString();
        for (EngineConfig engine : engines) {
            int start = 0;
            while (hits.size() < BulkJob.FILTER_ITEM_CAP) {
                FlowablePage page = cases
                        ? cmmn.listFinishedCases(
                                engine, CallPriority.HOUSEKEEPING, key, request.businessKey(), before, start, PAGE)
                        : flowable.listFinishedProcesses(
                                engine, CallPriority.HOUSEKEEPING, key, request.businessKey(), before, start, PAGE);
                List<Map<String, Object>> rows = page == null ? List.of() : page.dataOrEmpty();
                if (rows.isEmpty()) {
                    break;
                }
                for (Map<String, Object> row : rows) {
                    if (row.get("endTime") == null) {
                        continue;
                    }
                    if (!versionMatches(row, key, request.definitionVersion())) {
                        continue;
                    }
                    String businessKey = str(row, "businessKey");
                    if (heldBusiness(businessKey)) {
                        refuseHeld(auth, key, businessKey);
                    }
                    String id = str(row, "id");
                    if (id != null) {
                        hits.add(new Hit(engine.id(), id));
                    }
                    if (hits.size() == BulkJob.FILTER_ITEM_CAP) {
                        capped = page.total() > start + rows.size();
                        break;
                    }
                }
                start += rows.size();
                if (page.total() <= start) {
                    break;
                }
            }
        }
        return new Resolved(hits, capped, prod, cases);
    }

    private void refuseHeld(Authentication auth, String key, String businessKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("definitionKey", key);
        payload.put("businessKey", businessKey);
        audit.recordConfigEvent("history-hold-refused", auth.getName(), false, payload);
        throw BulkFilterResolution.refuse(
                HttpStatus.CONFLICT,
                "history-held",
                "A housekeeping hold covers this definition or business key. Nothing happened.");
    }

    private List<EngineConfig> engines(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return registry.all();
        }
        return ids.stream().map(registry::require).toList();
    }

    private static Instant parseBefore(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST,
                    "finished-before-required",
                    "An ended-before bound is required. Nothing happened.");
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException e) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST, "finished-before-invalid", "finishedBefore must be an ISO-8601 instant.");
        }
    }

    private boolean heldBusiness(String businessKey) {
        if (businessKey == null) {
            return false;
        }
        for (String pattern : heldBusinessKeys) {
            if (pattern.endsWith("*") && businessKey.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
            if (pattern.equals(businessKey)) {
                return true;
            }
        }
        return false;
    }

    private static boolean versionMatches(Map<String, Object> row, String key, Integer version) {
        if (version == null) {
            return true;
        }
        String id = str(row, "processDefinitionId");
        if (id == null) {
            id = str(row, "caseDefinitionId");
        }
        if (id == null) {
            return false;
        }
        String prefix = (key == null ? "" : key) + ":" + version + ":";
        return id.startsWith(prefix);
    }

    private static String str(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : value.toString();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return List.copyOf(out);
    }

    private record Hit(String engineId, String id) {}

    private record Resolved(List<Hit> hits, boolean capped, boolean prod, boolean cases) {}
}
