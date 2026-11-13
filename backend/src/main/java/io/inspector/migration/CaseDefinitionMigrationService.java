package io.inspector.migration;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.ActionResult;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.action.TicketPolicy;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.BreakGlassActor;
import io.inspector.bulk.BulkCountDriftException;
import io.inspector.client.CaseMigrationClient;
import io.inspector.client.CaseMigrationRoute;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.ForwardedActor;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.cmmn.CaseMigrationPrecheck;
import io.inspector.cmmn.CmmnCapabilities;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.migration.CaseDefinitionVersionsResponse.CaseDefinitionVersion;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.security.reauth.DangerousActionReauthGate;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The case-definition on-ramp and the three case-migration doors that exist on Flowable 7.x only
 * (#500, INSTANCE-MIGRATION.md §7.3):
 *
 * <ul>
 *   <li>{@link #migrateBatch} — {@code case-definitions/{id}/batch-migrate}: every RUNNING case of
 *       one version, as an engine batch. It cannot filter and runs no per-case check, so the BFF
 *       sends it only when the target model keeps every plan-item definition of the source;
 *       otherwise the cohort door (analyze, then migrate the clean cases) is the way.
 *   <li>{@link #migrateHistoric} — {@code case-definitions/{id}/migrate-historic-instances}: the
 *       ENDED cases of one version. History only.
 *   <li>{@link #migrateHistoricCase} — {@code historic-case-instances/{id}/migrate}: one ended case.
 * </ul>
 *
 * Every door is tier 3: ADMIN, writable engine, the CMMN capability, the 7.x version gate (refused
 * before any engine call on an older engine), re-auth, reason ≥ 10, ticket per engine policy, a
 * server-fresh restatement, the typed count (or case id) on prod, a fail-closed audit row with
 * payload {@code migrate-case/v1}, ONE engine call that is never retried, and an honest close-out.
 */
@Service
public class CaseDefinitionMigrationService {

    public static final String BATCH_ACTION = "migrate-cases-batch";
    public static final String HISTORIC_ACTION = "migrate-historic-cases";
    public static final String HISTORIC_CASE_ACTION = "migrate-historic-case";

    private static final int VERSION_CAP = 50;
    private static final int BATCH_LIST_CAP = 20;
    private static final int NOT_SUCCEEDED_SAMPLE = 20;
    private static final String PART_SUCCESS = "success";

    private final EngineRegistry registry;
    private final CmmnApiClient cmmn;
    private final CaseMigrationClient cases;
    private final CaseMigrationPrecheck precheck;
    private final RbacAuthorizer rbac;
    private final AuditService audit;
    private final TicketPolicy ticketPolicy;
    private final DangerousActionReauthGate reauth;

    public CaseDefinitionMigrationService(
            EngineRegistry registry,
            CmmnApiClient cmmn,
            CaseMigrationClient cases,
            RbacAuthorizer rbac,
            AuditService audit,
            TicketPolicy ticketPolicy,
            DangerousActionReauthGate reauth) {
        this.registry = registry;
        this.cmmn = cmmn;
        this.cases = cases;
        this.precheck = new CaseMigrationPrecheck(cmmn);
        this.rbac = rbac;
        this.audit = audit;
        this.ticketPolicy = ticketPolicy;
        this.reauth = reauth;
    }

    /* ------------------------------- reads ------------------------------- */

    public CaseDefinitionVersionsResponse versions(String engineId, String key) {
        EngineConfig engine = registry.require(engineId);
        CmmnCapabilities.requireScopeType(registry, engine);
        return preFlight(engine, () -> {
            FlowablePage newest =
                    cases.listCaseDefinitionVersionsDesc(engine, CallPriority.INTERACTIVE, key, VERSION_CAP);
            List<Map<String, Object>> definitions = newest != null ? newest.dataOrEmpty() : List.of();
            if (definitions.isEmpty()) {
                throw new GuardRefusedException(
                        HttpStatus.NOT_FOUND,
                        "unknown-definition-key",
                        "No deployed version of case key '" + key + "' exists on '" + engine.id() + "'.");
            }
            int latest = ((Number) definitions.get(0).get("version")).intValue();
            List<CaseDefinitionVersion> versions = new ArrayList<>();
            for (Map<String, Object> d : definitions) {
                String id = String.valueOf(d.get("id"));
                int version = ((Number) d.get("version")).intValue();
                versions.add(new CaseDefinitionVersion(
                        id,
                        version,
                        d.get("name") != null ? String.valueOf(d.get("name")) : null,
                        d.get("deploymentId") != null ? String.valueOf(d.get("deploymentId")) : null,
                        cmmn.countRunningCases(engine, CallPriority.INTERACTIVE, id),
                        cases.countEndedCases(engine, CallPriority.INTERACTIVE, id),
                        version == latest));
            }
            versions.sort(
                    Comparator.comparingInt(CaseDefinitionVersion::version).reversed());
            int total = (int) newest.total();
            return new CaseDefinitionVersionsResponse(
                    engineId,
                    key,
                    latest,
                    total,
                    total <= versions.size(),
                    versions,
                    CaseMigrationGate.reason(registry, engine));
        });
    }

    /** The engine's case-migration batches whose source is this definition, newest first. */
    public List<CaseMigrationBatchView> batches(String engineId, String caseDefinitionId) {
        EngineConfig engine = registry.require(engineId);
        CmmnCapabilities.requireScopeType(registry, engine);
        if (CaseMigrationGate.reason(registry, engine) != null) {
            return List.of(); // no batch route below 7.x, so there is nothing to list
        }
        return preFlight(engine, () -> {
            FlowablePage page =
                    cases.listCaseMigrationBatches(engine, CallPriority.INTERACTIVE, caseDefinitionId, BATCH_LIST_CAP);
            List<CaseMigrationBatchView> views = new ArrayList<>();
            for (Map<String, Object> batch : page != null ? page.dataOrEmpty() : List.<Map<String, Object>>of()) {
                views.add(batchView(engine, batch));
            }
            views.sort(Comparator.comparing(
                            CaseMigrationBatchView::createTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .reversed());
            return List.copyOf(views);
        });
    }

    private CaseMigrationBatchView batchView(EngineConfig engine, Map<String, Object> batch) {
        String id = String.valueOf(batch.get("id"));
        List<Map<String, Object>> parts = cases.listBatchParts(engine, CallPriority.INTERACTIVE, id);
        Map<String, Integer> byStatus = new TreeMap<>();
        List<String> notSucceeded = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            String status = String.valueOf(part.get("status"));
            byStatus.merge(status, 1, Integer::sum);
            if (!PART_SUCCESS.equals(status) && notSucceeded.size() < NOT_SUCCEEDED_SAMPLE) {
                notSucceeded.add(String.valueOf(part.get("scopeId")));
            }
        }
        return new CaseMigrationBatchView(
                id,
                str(batch.get("status")),
                str(batch.get("createTime")),
                str(batch.get("completeTime")),
                str(batch.get("searchKey")),
                str(batch.get("searchKey2")),
                parts.size(),
                byStatus,
                List.copyOf(notSucceeded));
    }

    /* ------------------------------- the 7.x doors ------------------------------- */

    public ActionResult migrateBatch(
            String engineId, String caseDefinitionId, CaseDefinitionMigrationRequest request, Authentication auth) {
        return execute(
                engineId,
                CaseMigrationRoute.BY_DEFINITION_BATCH,
                BATCH_ACTION,
                null,
                caseDefinitionId,
                request,
                auth,
                engine -> {
                    Versions pair = versionsOf(engine, caseDefinitionId, request);
                    Set<String> removed = new TreeSet<>(precheck.planDefinitionIds(engine, pair.fromId(), pair.from()));
                    if (removed.isEmpty()) {
                        // Nothing read from the source proves nothing about the target: fail closed.
                        throw new GuardRefusedException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "source-model-unreadable",
                                "No plan-item definition could be read from the source case model "
                                        + pair.fromId() + ", so the batch cannot be checked against the target."
                                        + " A by-definition batch moves every running case without a per-case"
                                        + " check. Use the cohort door instead. Nothing happened.");
                    }
                    removed.removeAll(precheck.planDefinitionIds(engine, pair.toId(), pair.to()));
                    if (!removed.isEmpty()) {
                        throw new GuardRefusedException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "plan-item-not-in-target",
                                "The target case model has no plan-item definition(s) " + removed + " of the source."
                                        + " A by-definition batch moves every running case without a per-case"
                                        + " check. Use the cohort door instead: it analyzes each case and migrates"
                                        + " only the clean ones. Nothing happened.");
                    }
                    long running = cmmn.countRunningCases(engine, CallPriority.INTERACTIVE, pair.fromId());
                    return new Plan(pair, running, "running", null);
                },
                (engine, plan) -> {
                    String batchId = newestBatchId(engine, plan.pair());
                    return new Outcome(
                            "{\"batchId\":" + (batchId == null ? "null" : "\"" + batchId + "\"") + "}",
                            "The engine accepted a batch migration of " + cases(plan.count(), "running") + " from "
                                    + plan.pair().fromId() + " to "
                                    + plan.pair().toId()
                                    + (batchId == null ? "" : " (batch " + batchId + ")")
                                    + ". The batch runs in the engine: no case has moved because of this answer"
                                    + " alone. Check the batch for the per-case outcome.");
                });
    }

    public ActionResult migrateHistoric(
            String engineId, String caseDefinitionId, CaseDefinitionMigrationRequest request, Authentication auth) {
        return execute(
                engineId,
                CaseMigrationRoute.BY_DEFINITION_HISTORIC,
                HISTORIC_ACTION,
                null,
                caseDefinitionId,
                request,
                auth,
                engine -> {
                    Versions pair = versionsOf(engine, caseDefinitionId, request);
                    long ended = cases.countEndedCases(engine, CallPriority.INTERACTIVE, pair.fromId());
                    return new Plan(pair, ended, "ended", null);
                },
                (engine, plan) -> {
                    long left = cases.countEndedCases(
                            engine, CallPriority.INTERACTIVE, plan.pair().fromId());
                    return new Outcome(
                            "{\"endedCasesLeftOnSource\":" + left + "}",
                            "History of " + cases(plan.count(), "ended") + " re-pointed from "
                                    + plan.pair().fromId() + " to "
                                    + plan.pair().toId() + ". " + left
                                    + " ended case(s) are left on the source definition.");
                });
    }

    public ActionResult migrateHistoricCase(
            String engineId, String caseInstanceId, CaseDefinitionMigrationRequest request, Authentication auth) {
        return execute(
                engineId,
                CaseMigrationRoute.HISTORIC,
                HISTORIC_CASE_ACTION,
                caseInstanceId,
                caseInstanceId,
                request,
                auth,
                engine -> {
                    Map<String, Object> historic =
                            cmmn.getHistoricCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseInstanceId);
                    if (historic == null) {
                        throw new GuardRefusedException(
                                HttpStatus.NOT_FOUND,
                                "case-not-found",
                                "Case " + caseInstanceId + " is not in the history of '" + engine.id()
                                        + "'. Nothing happened.");
                    }
                    if (historic.get("endTime") == null) {
                        throw new GuardRefusedException(
                                HttpStatus.CONFLICT,
                                "case-still-running",
                                "Case " + caseInstanceId + " has not ended. The engine migrates a running case only"
                                        + " through migrate-case. Nothing happened.");
                    }
                    Versions pair = versionsOf(engine, str(historic.get("caseDefinitionId")), request);
                    return new Plan(pair, 1, "ended", caseInstanceId);
                },
                (engine, plan) -> {
                    Map<String, Object> historic =
                            cmmn.getHistoricCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseInstanceId);
                    String observed = historic == null ? "(gone)" : str(historic.get("caseDefinitionId"));
                    return new Outcome(
                            "{\"observedCaseDefinitionId\":\"" + observed + "\"}",
                            "History of ended case " + caseInstanceId + " re-pointed to case definition " + observed
                                    + ".");
                });
    }

    private ActionResult execute(
            String engineId,
            CaseMigrationRoute route,
            String action,
            String auditInstanceId,
            String routeId,
            CaseDefinitionMigrationRequest body,
            Authentication auth,
            Function<EngineConfig, Plan> planner,
            java.util.function.BiFunction<EngineConfig, Plan, Outcome> observer) {
        EngineConfig engine = registry.require(engineId);
        CaseDefinitionMigrationRequest request =
                body != null ? body : new CaseDefinitionMigrationRequest(null, null, null, null, null);

        // -- guards: everything above the audit insert refuses with "nothing happened" --
        if (auth == null || !rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "'" + action + "' (tier 3) requires ADMIN on engine '" + engineId + "' (every environment).");
        }
        requireWritableEngine(engine, action);
        CmmnCapabilities.requireScopeType(registry, engine);
        // The version gate comes before any engine read: a 6.x engine has no such route.
        var health = registry.healthOf(engine.id());
        String version = health == null ? null : health.version();
        CaseMigrationGate.require7(
                registry,
                engine,
                "Case migration route " + route.path(),
                "Flowable 6.x has no such route; a single running case migrates through migrate-case");
        reauth.enforce(auth);
        String reason = requireReason(request.reason(), action);
        String ticketId = ticketPolicy.validate(request.ticketId(), engine.environment());

        Plan plan = preFlight(engine, () -> planner.apply(engine));
        if (plan.count() == 0) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "nothing-to-migrate",
                    "Case definition " + plan.pair().fromId() + " has no " + plan.noun() + " case. Nothing happened.");
        }
        requireConfirmation(engine, plan, request);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "migrate-case/v1");
        payload.put("route", route.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        payload.put("enginePath", route.path());
        payload.put("caseDefinitionKey", plan.pair().key());
        payload.put("fromCaseDefinitionId", plan.pair().fromId());
        payload.put("toCaseDefinitionId", plan.pair().toId());
        payload.put(plan.noun() + "Cases", plan.count());
        if (plan.caseId() != null) {
            payload.put("caseId", plan.caseId());
        }

        BreakGlassActor.set(rbac.isBreakGlass(auth));
        try {
            // -- fail-closed audit gate (R-AUD-01): beyond this point the attempt is on record --
            AuditEntry entry = audit.beginPending(
                    auth.getName(),
                    engineId,
                    blankToNull(engine.tenantId()),
                    auditInstanceId,
                    action,
                    reason,
                    ticketId,
                    payload,
                    engine.auditPayloadOrDefault());
            dispatchAudited(
                    entry,
                    engineId,
                    () -> cmmn.migrateCase(
                            engine,
                            CallPriority.INTERACTIVE,
                            version,
                            route,
                            routeId,
                            Map.of("toCaseDefinitionId", plan.pair().toId())));
            Outcome outcome;
            try {
                outcome = observer.apply(engine, plan);
            } catch (RuntimeException e) {
                outcome = new Outcome(
                        null,
                        "The engine accepted the call (HTTP 200). The follow-up read did not answer, so the result"
                                + " is not verified here.");
            }
            audit.close(entry, AuditOutcome.ok, 200, outcome.snippet(), true);
            return new ActionResult(entry.getId(), entry.getCorrelationId(), "ok", 200, outcome.delta());
        } finally {
            BreakGlassActor.clear();
        }
    }

    /* ------------------------------- restatement ------------------------------- */

    /** Source and target: both deployed, the same case key, and not the same version. */
    private Versions versionsOf(EngineConfig engine, String fromId, CaseDefinitionMigrationRequest request) {
        String toId = CaseCohortService.required(request.toCaseDefinitionId(), "toCaseDefinitionId");
        Map<String, Object> from = cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, fromId);
        if (from == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "source-not-deployed",
                    "Case definition " + fromId + " is not deployed on '" + engine.id() + "'. Nothing happened.");
        }
        if (toId.equals(fromId)) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "same-definition-target",
                    "Source and target are the same case definition " + fromId + ". Nothing happened.");
        }
        Map<String, Object> to = precheck.definition(engine, toId);
        if (from.get("key") == null || !from.get("key").equals(to.get("key"))) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "cross-key-target",
                    "Case definition " + toId + " (key '" + to.get("key") + "') is not a version of '" + from.get("key")
                            + "'. Nothing happened.");
        }
        return new Versions(String.valueOf(from.get("key")), fromId, from, toId, to);
    }

    private record Versions(String key, String fromId, Map<String, Object> from, String toId, Map<String, Object> to) {}

    /** {@code caseId} set means the single historic-case door: the prod token is that id, not a count. */
    private record Plan(Versions pair, long count, String noun, String caseId) {}

    private record Outcome(String snippet, String delta) {}

    /** Prod tier-3 escalation: the typed count of the restated set, or the typed case id. */
    private static void requireConfirmation(EngineConfig engine, Plan plan, CaseDefinitionMigrationRequest request) {
        if (engine.environment() != EngineEnvironment.PROD) {
            return;
        }
        if (plan.caseId() != null) {
            if (!plan.caseId().equals(request.confirmToken())) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "confirm-token-mismatch",
                        "Historic migration on a PROD engine is irreversible: type the case id (\"" + plan.caseId()
                                + "\") to confirm. Nothing happened.");
            }
            return;
        }
        if (request.confirmedCount() == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "confirm-count-required",
                    "This is a PRODUCTION engine — type the number of " + plan.noun() + " cases (" + plan.count()
                            + ") to confirm. Nothing happened.");
        }
        if (request.confirmedCount() != plan.count()) {
            throw new BulkCountDriftException(request.confirmedCount(), (int) plan.count());
        }
    }

    /** The batch this call just created: the newest one from this source to this target. */
    private String newestBatchId(EngineConfig engine, Versions pair) {
        FlowablePage page =
                cases.listCaseMigrationBatches(engine, CallPriority.INTERACTIVE, pair.fromId(), BATCH_LIST_CAP);
        return (page != null ? page.dataOrEmpty() : List.<Map<String, Object>>of())
                .stream()
                        .filter(batch -> pair.toId().equals(str(batch.get("searchKey2"))))
                        .max(Comparator.comparing(batch -> String.valueOf(batch.get("createTime"))))
                        .map(batch -> str(batch.get("id")))
                        .orElse(null);
    }

    /* ------------------------------- rails ------------------------------- */

    private static void requireWritableEngine(EngineConfig engine, String action) {
        if (!engine.enabled()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "engine-disabled",
                    "Engine '" + engine.id() + "' is disabled in the registry.");
        }
        if (engine.modeOrDefault() == EngineMode.READ_ONLY) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "engine-read-only",
                    "Engine '" + engine.id() + "' is registered read-only (R-GOV-04) — '" + action + "' is rejected.");
        }
    }

    private static String requireReason(String reason, String action) {
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "reason-required",
                    "'" + action + "' (tier 3) always requires a reason of at least 10 characters.");
        }
        if (normalized.trim().length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        return normalized.trim();
    }

    /** A pre-flight read. An open breaker or an unreachable engine is a refusal: nothing was sent. */
    private static <T> T preFlight(EngineConfig engine, java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (CallNotPermittedException | BulkheadFullException e) {
            throw new GuardRefusedException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "engine-shedding-load",
                    "Engine '" + engine.id() + "' is shedding load (circuit open) — nothing was sent.");
        } catch (ResourceAccessException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "engine-unreachable",
                    "Engine '" + engine.id() + "' did not answer the pre-flight read — nothing was sent.");
        }
    }

    /** The ONE engine call with the M4 outcome discipline — never retried, honest close-out. */
    private void dispatchAudited(AuditEntry entry, String engineId, Runnable call) {
        ForwardedActor.set(entry.forwardedIdentity());
        try {
            call.run();
        } catch (CallNotPermittedException | BulkheadFullException e) {
            audit.close(entry, AuditOutcome.failed, null, "refused: circuit open / bulkhead full", false);
            throw new GuardRefusedException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "engine-shedding-load",
                    "Engine '" + engineId + "' is shedding load (circuit open) — the migration was not sent.");
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            audit.close(entry, AuditOutcome.failed, e.getStatusCode().value(), responseBody, false);
            throw new EngineRejectedException(entry.getId(), e.getStatusCode().value(), responseBody);
        } catch (ResourceAccessException e) {
            if (notDispatched(e)) {
                audit.close(entry, AuditOutcome.failed, null, "engine unreachable: " + e.getMessage(), false);
                throw new GuardRefusedException(
                        HttpStatus.BAD_GATEWAY,
                        "engine-unreachable",
                        "Engine '" + engineId + "' is unreachable — the migration was not sent.");
            }
            // Timed out AFTER dispatch: the engine may have applied it. UNKNOWN, never re-fired.
            audit.close(entry, AuditOutcome.unknown, null, "no answer within write budget: " + e.getMessage(), false);
            throw new OutcomeUnknownException(entry.getId(), e);
        } finally {
            ForwardedActor.clear();
        }
    }

    private static boolean notDispatched(ResourceAccessException e) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private static String cases(long count, String noun) {
        return count + " " + noun + " case" + (count == 1 ? "" : "s");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
