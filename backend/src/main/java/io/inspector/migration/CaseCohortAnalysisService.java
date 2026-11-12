package io.inspector.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.cmmn.CaseMigrationPrecheck;
import io.inspector.cmmn.CasePlanDefinitionIds;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.migration.CaseCohortAnalysisView.BlockedCase;
import io.inspector.migration.CaseCohortAnalysisView.PlanItemAggregate;
import io.inspector.migration.CaseCohortService.ListedCohort;
import io.inspector.migration.MigrationAnalysis.Status;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

/**
 * Case cohort analysis (#500): lists the cohort, then runs the single-case pre-check once per
 * case — its live plan-item definition ids against the target case model, read once. No extra
 * vocabulary: a case is a blocker (with the reason) or clean. Each clean case's definition id and
 * plan-item fingerprint are stored as the compare-and-set baseline of the bulk {@code
 * migrate-case} job.
 *
 * <p>Rows live in {@code migration_analysis} beside the process analyses, told apart by
 * {@link #CASE_TAXONOMY_VERSION}: the §14 BPMN finding taxonomy does not apply to a case. The
 * process readers refuse such a row, and this class refuses a process row.
 */
@Service
public class CaseCohortAnalysisService {

    /** {@code taxonomy_version} of a case analysis row. Process rows carry the §14 version (≥ 1). */
    public static final int CASE_TAXONOMY_VERSION = 0;

    static final String BLOCKER_CODE = "PLAN_ITEM_NOT_IN_TARGET";
    private static final int SNAPSHOT_EVERY = 25;

    private static final Logger log = LoggerFactory.getLogger(CaseCohortAnalysisService.class);

    private final CaseCohortService cohorts;
    private final CmmnApiClient cmmn;
    private final CaseMigrationPrecheck precheck;
    private final EngineRegistry registry;
    private final MigrationAnalysisRepository analyses;
    private final RbacAuthorizer rbac;
    private final ObjectMapper json;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Executor executor;

    @Autowired
    public CaseCohortAnalysisService(
            CaseCohortService cohorts,
            CmmnApiClient cmmn,
            EngineRegistry registry,
            MigrationAnalysisRepository analyses,
            RbacAuthorizer rbac,
            ObjectMapper json,
            Clock clock,
            ApplicationEventPublisher events) {
        this(
                cohorts,
                cmmn,
                registry,
                analyses,
                rbac,
                json,
                clock,
                events,
                MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor());
    }

    CaseCohortAnalysisService(
            CaseCohortService cohorts,
            CmmnApiClient cmmn,
            EngineRegistry registry,
            MigrationAnalysisRepository analyses,
            RbacAuthorizer rbac,
            ObjectMapper json,
            Clock clock,
            ApplicationEventPublisher events,
            Executor executor) {
        this.cohorts = cohorts;
        this.cmmn = cmmn;
        this.precheck = new CaseMigrationPrecheck(cmmn);
        this.registry = registry;
        this.analyses = analyses;
        this.rbac = rbac;
        this.json = json;
        this.clock = clock;
        this.events = events;
        this.executor = executor;
    }

    /** True for a row this class wrote. The process analysis readers refuse it. */
    public static boolean isCaseAnalysis(MigrationAnalysis row) {
        return row.getTaxonomyVersion() == CASE_TAXONOMY_VERSION;
    }

    public CaseCohortAnalysisView start(String engineId, CaseCohortRequest request, Authentication auth) {
        requireAdmin(auth, engineId);
        ListedCohort page = cohorts.listCases(engineId, request);
        EngineConfig engine = registry.require(engineId);
        String toId = CaseCohortService.required(page.toCaseDefinitionId(), "toCaseDefinitionId");
        Set<String> targetIds = targetModel(engine, page.fromCaseDefinitionId(), toId);

        MigrationAnalysis row = new MigrationAnalysis(
                UUID.randomUUID(),
                engineId,
                write(request),
                page.fromCaseDefinitionId(),
                toId,
                CASE_TAXONOMY_VERSION,
                write(Map.of()),
                write(List.of()),
                write(payload(page, List.of(), Map.of(), false, null)),
                clock.instant());
        analyses.save(row);
        publish(row.getId());
        executor.execute(() -> {
            try {
                run(row, engine, page, targetIds);
            } catch (RuntimeException e) {
                log.error("case cohort analysis {} failed", row.getId(), e);
                try {
                    row.fail(clock.instant());
                    analyses.save(row);
                    publish(row.getId());
                } catch (RuntimeException nested) {
                    log.error("case cohort analysis {} could not be marked failed", row.getId(), nested);
                }
            }
        });
        return toView(analyses.findById(row.getId()).orElse(row));
    }

    public CaseCohortAnalysisView get(UUID id, Authentication auth) {
        MigrationAnalysis row = require(analyses, id);
        requireAdmin(auth, row.getEngineId());
        return toView(row);
    }

    /** The stored row, or 404 — also for a process analysis id, which is not a case analysis. */
    static MigrationAnalysis require(MigrationAnalysisRepository analyses, UUID id) {
        return analyses.findById(id)
                .filter(CaseCohortAnalysisService::isCaseAnalysis)
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.NOT_FOUND, "analysis-not-found", "No case migration analysis " + id + "."));
    }

    /**
     * Source and target must be deployed versions of the same case key, and differ. Returns the
     * target model's plan-item definition ids — one read for the whole cohort.
     */
    private Set<String> targetModel(EngineConfig engine, String fromId, String toId) {
        if (fromId.equals(toId)) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "same-definition-target",
                    "Source and target are the same case definition " + fromId + ". Nothing happened.");
        }
        Map<String, Object> source = precheck.definition(engine, fromId);
        Map<String, Object> target = precheck.definition(engine, toId);
        if (source.get("key") == null || !source.get("key").equals(target.get("key"))) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "cross-key-target",
                    "Case definition " + toId + " (key '" + target.get("key") + "') is not a version of '"
                            + source.get("key") + "'. Nothing happened.");
        }
        return precheck.planDefinitionIds(engine, toId, target);
    }

    private void run(MigrationAnalysis row, EngineConfig engine, ListedCohort page, Set<String> targetIds) {
        List<Analyzed> done = new ArrayList<>();
        Map<String, String> digests = new LinkedHashMap<>();
        String failure = null;
        for (String caseId : page.caseIds()) {
            try {
                Analyzed analyzed = analyze(engine, caseId, page.fromCaseDefinitionId(), targetIds);
                done.add(analyzed);
                if (analyzed.reason() == null) {
                    digests.put(caseId, analyzed.digest());
                }
            } catch (GuardRefusedException e) {
                done.add(new Analyzed(caseId, List.of(), e.getMessage(), null));
            } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
                // The engine stopped answering: what was analyzed so far is a lower bound.
                failure = "engine '" + engine.id() + "' did not answer the pre-check read: " + e.getMessage();
                break;
            }
            if (done.size() % SNAPSHOT_EVERY == 0) {
                snapshot(row, page, done, digests, false, null);
            }
        }
        snapshot(row, page, done, digests, true, failure);
    }

    private Analyzed analyze(EngineConfig engine, String caseId, String fromId, Set<String> targetIds) {
        Map<String, Object> running = cmmn.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseId);
        if (running == null) {
            return new Analyzed(caseId, List.of(), "Case " + caseId + " is no longer running.", null);
        }
        if (!fromId.equals(String.valueOf(running.get("caseDefinitionId")))) {
            return new Analyzed(
                    caseId,
                    List.of(),
                    "Case " + caseId + " left case definition " + fromId + " during the analysis.",
                    null);
        }
        List<String> active = precheck.activePlanItemDefinitionIds(engine, caseId);
        List<String> absent = CasePlanDefinitionIds.missing(active, targetIds);
        if (!absent.isEmpty()) {
            return new Analyzed(
                    caseId,
                    absent,
                    "Active plan-item definition id(s) " + absent + " are not in the target case model.",
                    null);
        }
        return new Analyzed(caseId, List.of(), null, CasePlanDefinitionIds.stateDigest(active));
    }

    /** One analyzed case. {@code reason} null means clean; {@code absent} names the missing definition ids. */
    private record Analyzed(String caseId, List<String> absent, String reason, String digest) {}

    private void snapshot(
            MigrationAnalysis row,
            ListedCohort page,
            List<Analyzed> done,
            Map<String, String> digests,
            boolean terminal,
            String failure) {
        boolean stopped = failure != null;
        Status status = !terminal ? Status.RUNNING : stopped ? Status.FAILED : Status.COMPLETED;
        Payload payload = payload(page, done, digests, stopped, failure);
        row.update(
                status,
                row.getTargetDefinitionId(),
                write(payload.blockerCount() == 0 ? Map.of() : Map.of(BLOCKER_CODE, payload.blockerCount())),
                write(done.stream().map(Analyzed::caseId).toList()),
                write(payload),
                terminal ? clock.instant() : null);
        analyses.save(row);
        publish(row.getId());
    }

    private static Payload payload(
            ListedCohort page, List<Analyzed> done, Map<String, String> digests, boolean stopped, String failure) {
        List<BlockedCase> blockers = new ArrayList<>();
        List<String> executable = new ArrayList<>();
        Map<String, Integer> byPlanItem = new TreeMap<>();
        for (Analyzed analyzed : done) {
            if (analyzed.reason() == null) {
                executable.add(analyzed.caseId());
                continue;
            }
            blockers.add(new BlockedCase(analyzed.caseId(), analyzed.reason()));
            analyzed.absent().stream().distinct().forEach(id -> byPlanItem.merge(id, 1, Integer::sum));
        }
        boolean lowerBound = page.lowerBound() || stopped;
        String label = !lowerBound
                ? null
                : "lower bound — estimated " + done.size() + " of " + page.total() + " running cases"
                        + (failure == null ? "" : " (" + failure + ")");
        return new Payload(
                lowerBound,
                label,
                page.total(),
                blockers.size(),
                executable.size(),
                byPlanItem.entrySet().stream()
                        .map(entry -> new PlanItemAggregate(entry.getKey(), entry.getValue()))
                        .toList(),
                List.copyOf(blockers),
                List.copyOf(executable),
                failure,
                Map.copyOf(digests));
    }

    private CaseCohortAnalysisView toView(MigrationAnalysis row) {
        Payload payload = read(row.getPayload(), Payload.class);
        return new CaseCohortAnalysisView(
                row.getId(),
                row.getEngineId(),
                row.getStatus().name(),
                row.getSourceDefinitionId(),
                row.getTargetDefinitionId(),
                read(row.getCohortFilter(), CaseCohortRequest.class),
                payload.blockerCount(),
                payload.cleanCount(),
                payload.byPlanItem() == null ? List.of() : payload.byPlanItem(),
                payload.blockers() == null ? List.of() : payload.blockers(),
                payload.executableIds() == null ? List.of() : payload.executableIds(),
                payload.cohortTotal(),
                payload.lowerBound(),
                payload.boundLabel(),
                payload.failure(),
                CaseCohortAnalysisView.BANNER);
    }

    private void publish(UUID id) {
        events.publishEvent(new MigrationAnalysisChangedEvent(id));
    }

    private void requireAdmin(Authentication auth, String engineId) {
        if (auth == null || !rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN, "rbac-denied", "Case cohort analysis requires ADMIN on this engine.");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("case migration analysis could not be stored", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("case migration analysis could not be read", e);
        }
    }

    /**
     * The stored result. {@code digests} maps each clean case to its plan-item fingerprint
     * ({@link CasePlanDefinitionIds#stateDigest}); the source definition id is a column.
     */
    public record Payload(
            boolean lowerBound,
            String boundLabel,
            long cohortTotal,
            int blockerCount,
            int cleanCount,
            List<PlanItemAggregate> byPlanItem,
            List<BlockedCase> blockers,
            List<String> executableIds,
            String failure,
            Map<String, String> digests) {}
}
