package io.inspector.bulk;

import io.inspector.action.GuardRefusedException;
import io.inspector.aggregate.SearchService;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.registry.EngineRegistry;
import io.inspector.triage.ErrorSignatureNormalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * v1.x fast follow #1 (SPEC §7/§8): error-class group retry from the triage landing.
 *
 * <p>Server-side resolution is BINDING: the browser submits only the group's coordinates
 * (signature hash + definition key, optionally a version and/or one engine) and the BFF
 * re-resolves the members itself — through {@link SearchService}, i.e. the same capped
 * failure-lane scan + signature-refinement bridge the triage cards were aggregated from,
 * never the grid-search plan and never a client-supplied ID list. The resolved targets then
 * ride the unchanged M5 rails ({@link BulkJobService#submit}): 200-item cap, per-item RBAC
 * re-run, protected auto-exclusion, fail-closed envelope audit, sequential per-item fan-out.
 *
 * <p>{@code definitionVersion} is OPTIONAL (#105 remainder): omitting it retries the error
 * class across every deployed version of the definition key in one job, rather than one
 * {@code defKey:vN} slice at a time — {@link SearchService} already resolves a null version
 * to every deployed definition ID for the key (the un-scoped case is its most common path).
 *
 * <p>Only FAILED (dead-letter-bearing) members are targeted — RETRYING instances still own
 * live retry cycles and may self-heal; retrying them is not this verb's job.
 */
@Service
public class BulkErrorClassService {

    private final SearchService search;
    private final BulkJobService bulk;
    private final EngineRegistry registry;

    public BulkErrorClassService(SearchService search, BulkJobService bulk, EngineRegistry registry) {
        this.search = search;
        this.bulk = bulk;
        this.registry = registry;
    }

    public BulkDtos.BulkJobDto submit(BulkDtos.BulkErrorClassRequest request, Authentication auth) {
        if (isBlank(request.signatureHash())) {
            throw refuse(HttpStatus.BAD_REQUEST, "error-class-signature-required", "signatureHash is required.");
        }
        if (request.algoVersion() == null || request.algoVersion() != ErrorSignatureNormalizer.ALGO_VERSION) {
            // A hash from another normalizer generation would silently match nothing (or,
            // worse, the wrong group) — refuse loudly instead.
            throw refuse(
                    HttpStatus.CONFLICT,
                    "error-class-algo-mismatch",
                    "This card was rendered with signature algorithm v" + request.algoVersion()
                            + " but the BFF now computes v" + ErrorSignatureNormalizer.ALGO_VERSION
                            + " — refresh the triage landing. Nothing happened.");
        }
        if (isBlank(request.processDefinitionKey())) {
            throw refuse(
                    HttpStatus.BAD_REQUEST,
                    "error-class-definition-required",
                    "Group retry requires processDefinitionKey. definitionVersion is optional —"
                            + " omit it to retry the whole definition across every deployed version.");
        }
        // A group retry acts on instances the operator never enumerated — the reason is
        // mandatory (same rule as every other bulk door, BulkJobService#submit).
        if (request.reason() == null
                || request.reason().isBlank()
                || request.reason().length() < 10) {
            throw refuse(HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        List<String> engineIds = null;
        if (!isBlank(request.engineId())) {
            registry.require(request.engineId());
            engineIds = List.of(request.engineId());
        }

        SearchRequest query = new SearchRequest(
                engineIds,
                List.of(InstanceStatus.FAILED),
                request.processDefinitionKey(),
                request.definitionVersion(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                request.signatureHash(),
                null,
                null,
                null,
                BulkJob.ITEM_CAP + 1);
        SearchResponse resolved = search.search(query);

        // Fail-closed resolution: an engine that did not answer the scan means the member
        // list is not trustworthy — refuse the whole submit rather than retry a subset the
        // operator never saw.
        for (Map.Entry<String, SearchResponse.EngineResult> e :
                resolved.perEngine().entrySet()) {
            if (!e.getValue().ok()) {
                throw refuse(
                        HttpStatus.BAD_GATEWAY,
                        "error-class-resolution-degraded",
                        "Engine '" + e.getKey() + "' did not answer the group-resolution scan ("
                                + e.getValue().error() + ") — the member list would be incomplete."
                                + " Nothing happened.");
            }
        }

        Map<String, BulkDtos.BulkTarget> targets = new LinkedHashMap<>();
        for (ProcessInstanceRow row : resolved.rows()) {
            targets.putIfAbsent(
                    row.compositeId(), new BulkDtos.BulkTarget(row.engineId(), row.processInstanceId(), null));
        }
        if (targets.isEmpty()) {
            throw refuse(
                    HttpStatus.CONFLICT,
                    "error-class-drained",
                    "No FAILED instances currently match this signature — the group has drained"
                            + " since the card rendered. Refresh the triage landing. Nothing happened.");
        }
        if (targets.size() > BulkJob.ITEM_CAP) {
            throw refuse(
                    HttpStatus.BAD_REQUEST,
                    "bulk-cap-exceeded",
                    "This group resolves to more than " + BulkJob.ITEM_CAP + " FAILED instances —"
                            + " over the bulk cap. Narrow to a single engine or definition version."
                            + " Nothing happened.");
        }

        boolean scanTruncated =
                resolved.perEngine().values().stream().anyMatch(r -> r.dlqScan() != null || r.failingScan() != null);
        // Group provenance for the envelope audit row: which signature, which scan
        // generation, how many members it resolved to, and whether the scan was capped
        // (a truncated scan means the group may hold members this job never saw).
        Map<String, Object> groupMeta = new LinkedHashMap<>();
        groupMeta.put("signatureHash", request.signatureHash());
        groupMeta.put("algoVersion", ErrorSignatureNormalizer.ALGO_VERSION);
        groupMeta.put(
                "definition",
                request.definitionVersion() != null
                        ? request.processDefinitionKey() + ":v" + request.definitionVersion()
                        : request.processDefinitionKey() + " (all versions)");
        if (engineIds != null) {
            groupMeta.put("engineId", request.engineId());
        }
        groupMeta.put("resolvedCount", targets.size());
        groupMeta.put("scanTruncated", scanTruncated);

        BulkDtos.BulkSubmitRequest submit = new BulkDtos.BulkSubmitRequest(
                "retry-job", request.reason(), request.ticketId(), null, List.copyOf(targets.values()));
        // Scope provenance (usability fix E1): definition key + version identify the group
        // at a glance in the operations drawer, without re-deriving it from the envelope. A
        // version-omitted request spans every deployed version — say so rather than "vnull".
        String scopeLabel = (request.definitionVersion() != null
                        ? request.processDefinitionKey() + " v" + request.definitionVersion()
                        : request.processDefinitionKey() + " (all versions)")
                + " · error class";
        return bulk.submit(submit, auth, Map.of("errorClass", groupMeta), scopeLabel);
    }

    private static GuardRefusedException refuse(HttpStatus status, String code, String message) {
        return new GuardRefusedException(status, code, message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
