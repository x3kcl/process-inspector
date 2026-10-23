package io.inspector.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.GuardRefusedException;
import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.migration.CohortFindingAggregator.Instance;
import io.inspector.migration.CohortFindingAggregator.Result;
import io.inspector.migration.CohortMigrationService.ListedCohort;
import io.inspector.migration.MigrationAnalysis.Status;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Cohort analysis (#495). Lists the S2 cohort, then calls {@link MigrationService#preview} once
 * per instance — the path that already computes §14 findings. No extra engine call, no
 * by-definition migrate.
 *
 * <p>ponytail: instances are previewed one at a time on the interactive lane preview already
 * uses. A BACKGROUND-priority overload of preview would be a second call path. Add a permit
 * pool only if the cohort cap rises past {@link CohortMigrationService#CAP}.
 */
@Service
public class CohortAnalysisService {

    static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = CohortMigrationService.CAP;

    private static final Logger log = LoggerFactory.getLogger(CohortAnalysisService.class);
    private static final TypeReference<Map<String, Integer>> CODE_COUNTS = new TypeReference<>() {};
    private static final TypeReference<List<String>> ID_LIST = new TypeReference<>() {};

    private final CohortMigrationService cohorts;
    private final MigrationService migration;
    private final MigrationAnalysisRepository analyses;
    private final RbacAuthorizer rbac;
    private final ObjectMapper json;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Executor executor;

    @Autowired
    public CohortAnalysisService(
            CohortMigrationService cohorts,
            MigrationService migration,
            MigrationAnalysisRepository analyses,
            RbacAuthorizer rbac,
            ObjectMapper json,
            Clock clock,
            ApplicationEventPublisher events) {
        this(
                cohorts,
                migration,
                analyses,
                rbac,
                json,
                clock,
                events,
                MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor());
    }

    CohortAnalysisService(
            CohortMigrationService cohorts,
            MigrationService migration,
            MigrationAnalysisRepository analyses,
            RbacAuthorizer rbac,
            ObjectMapper json,
            Clock clock,
            ApplicationEventPublisher events,
            Executor executor) {
        this.cohorts = cohorts;
        this.migration = migration;
        this.analyses = analyses;
        this.rbac = rbac;
        this.json = json;
        this.clock = clock;
        this.events = events;
        this.executor = executor;
    }

    public CohortAnalysisView start(String engineId, CohortPreviewRequest request, Authentication auth) {
        requireAdmin(auth, engineId);
        ListedCohort page = cohorts.listInstances(engineId, request);
        Payload initial = new Payload(
                page.lowerBound(),
                boundLabel(page.lowerBound(), 0, page.total(), null),
                page.total(),
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
        MigrationAnalysis row = new MigrationAnalysis(
                UUID.randomUUID(),
                engineId,
                write(request),
                page.fromDefinitionId(),
                blank(page.toDefinitionId()),
                MigrationFinding.TAXONOMY_VERSION,
                write(Map.of()),
                write(List.of()),
                write(initial),
                clock.instant());
        analyses.save(row);
        publish(row.getId());
        executor.execute(() -> {
            try {
                run(row, page, request, auth);
            } catch (RuntimeException e) {
                log.error("cohort analysis {} failed", row.getId(), e);
                try {
                    row.fail(clock.instant());
                    analyses.save(row);
                    publish(row.getId());
                } catch (RuntimeException nested) {
                    log.error("cohort analysis {} could not be marked failed", row.getId(), nested);
                }
            }
        });
        return toView(reload(row), 0, DEFAULT_PAGE_SIZE);
    }

    public CohortAnalysisView get(UUID id, int page, int size, Authentication auth) {
        MigrationAnalysis row = analyses.findById(id)
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.NOT_FOUND, "analysis-not-found", "No migration analysis " + id + "."));
        requireAdmin(auth, row.getEngineId());
        return toView(row, page, size);
    }

    private void run(MigrationAnalysis row, ListedCohort page, CohortPreviewRequest request, Authentication auth) {
        List<Instance> done = new ArrayList<>();
        String target = page.toDefinitionId();
        String failure = null;
        boolean stopped = false;
        MigrationRequest probe = new MigrationRequest(
                blank(page.toDefinitionId()), null, request.mappings(), null, null, null, null, null);
        for (String instanceId : page.instanceIds()) {
            try {
                MigrationPreview preview = migration.preview(row.getEngineId(), instanceId, probe, auth);
                if (blank(target) == null) {
                    target = preview.toProcessDefinitionId();
                }
                done.add(new Instance(instanceId, findingsOf(preview)));
            } catch (GuardRefusedException e) {
                done.add(new Instance(instanceId, List.of(), e.getMessage()));
                if ("engine-unreachable".equals(e.code())) {
                    failure = e.getMessage();
                    stopped = true;
                    break;
                }
            } catch (RuntimeException e) {
                done.add(new Instance(instanceId, List.of(), e.getMessage()));
                failure = e.getMessage();
                stopped = true;
                break;
            }
            snapshot(row, page, done, target, false, null);
        }
        snapshot(row, page, done, target, stopped, failure);
    }

    private void snapshot(
            MigrationAnalysis row,
            ListedCohort page,
            List<Instance> done,
            String target,
            boolean stopped,
            String failure) {
        Result result = CohortFindingAggregator.aggregate(done);
        boolean terminal = stopped || done.size() == page.instanceIds().size();
        boolean lowerBound = page.lowerBound() || stopped;
        Status status = !terminal ? Status.RUNNING : stopped ? Status.FAILED : Status.COMPLETED;
        Payload payload = new Payload(
                lowerBound,
                boundLabel(lowerBound, done.size(), page.total(), failure),
                page.total(),
                result.blockerCount(),
                result.warningCount(),
                result.cleanCount(),
                result.byActivity(),
                result.blockers(),
                result.executableIds(),
                result.instances(),
                failure);
        row.update(
                status,
                blank(target),
                write(result.codeCounts()),
                write(result.sampleInstanceIds()),
                write(payload),
                terminal ? clock.instant() : null);
        analyses.save(row);
        publish(row.getId());
    }

    private CohortAnalysisView toView(MigrationAnalysis row, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        Payload payload = read(row.getPayload(), Payload.class);
        List<CohortFindingAggregator.InstanceRow> all = payload.instances() == null ? List.of() : payload.instances();
        long start = (long) pageIndex * pageSize;
        int from = start >= all.size() ? all.size() : (int) start;
        int to = Math.min(from + pageSize, all.size());
        return new CohortAnalysisView(
                row.getId(),
                row.getEngineId(),
                row.getStatus().name(),
                row.getSourceDefinitionId(),
                row.getTargetDefinitionId(),
                row.getTaxonomyVersion(),
                read(row.getCohortFilter(), CohortPreviewRequest.class),
                read(row.getCodeCounts(), CODE_COUNTS),
                read(row.getSampleInstanceIds(), ID_LIST),
                payload.blockerCount(),
                payload.warningCount(),
                payload.cleanCount(),
                payload.byActivity() == null ? List.of() : payload.byActivity(),
                payload.blockers() == null ? List.of() : payload.blockers(),
                payload.executableIds() == null ? List.of() : payload.executableIds(),
                List.copyOf(all.subList(from, to)),
                pageIndex,
                pageSize,
                all.size(),
                payload.cohortTotal(),
                payload.lowerBound(),
                payload.boundLabel(),
                payload.failure(),
                CohortAnalysisView.BANNER);
    }

    private MigrationAnalysis reload(MigrationAnalysis row) {
        return analyses.findById(row.getId()).orElse(row);
    }

    private void publish(UUID id) {
        events.publishEvent(new MigrationAnalysisChangedEvent(id));
    }

    private static List<MigrationFinding> findingsOf(MigrationPreview preview) {
        List<MigrationFinding> all = new ArrayList<>();
        if (preview.activities() != null) {
            preview.activities().forEach(activity -> all.addAll(activity.findings()));
        }
        if (preview.findings() != null) {
            all.addAll(preview.findings());
        }
        return all;
    }

    private static String boundLabel(boolean lowerBound, int analyzed, long total, String failure) {
        if (!lowerBound) {
            return null;
        }
        String label = "lower bound — estimated " + analyzed + " of " + total + " running instances";
        return failure == null ? label : label + " (" + failure + ")";
    }

    private void requireAdmin(Authentication auth, String engineId) {
        if (auth == null || !rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN, "rbac-denied", "Cohort analysis requires ADMIN on this engine.");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("migration analysis could not be stored", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("migration analysis could not be read", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("migration analysis could not be read", e);
        }
    }

    /** The drill, the executable set, and the lower-bound label. Counts are also columns. */
    public record Payload(
            boolean lowerBound,
            String boundLabel,
            long cohortTotal,
            int blockerCount,
            int warningCount,
            int cleanCount,
            List<CohortFindingAggregator.ActivityAggregate> byActivity,
            List<CohortFindingAggregator.BlockedInstance> blockers,
            List<String> executableIds,
            List<CohortFindingAggregator.InstanceRow> instances,
            String failure) {}
}
