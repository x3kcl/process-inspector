package io.inspector.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
import io.inspector.migration.CohortBulkMigration.Cohort;
import io.inspector.migration.CohortFindingAggregator.BlockedInstance;
import io.inspector.migration.CohortMigrationService.ListedCohort;
import io.inspector.migration.HierarchyPlanGroup.Decision;
import io.inspector.migration.HierarchyPlanView.PlanGroupView;
import io.inspector.migration.HierarchyPlanView.PlanLevelView;
import io.inspector.migration.HierarchyTree.Caps;
import io.inspector.migration.HierarchyTree.Member;
import io.inspector.migration.HierarchyTree.PlanCircularReference;
import io.inspector.migration.HierarchyTree.PlanNode;
import io.inspector.migration.HierarchyTree.PlanTruncation;
import io.inspector.migration.HierarchyTree.Walk;
import io.inspector.migration.MigrationAnalysis.Status;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The hierarchy migration plan builder (#482 S5 / #497). Read side of "children first, then
 * parent":
 *
 * <ol>
 *   <li>{@link #create}: enumerate the RUNNING call-activity tree under the root(s) with the
 *       Hierarchy tab's own per-node read and caps (depth, breadth per node, total nodes), detect
 *       circular definition references, and store it. Nothing is migrated.
 *   <li>{@link #decide}: per source definition in the tree, the operator either skips it or
 *       configures a target and mapping. Configuring runs the cohort analysis (#495) over exactly
 *       that definition's instances, which stores each instance's compare-and-set digest.
 *   <li>{@link #execution}: what the bulk door submits — every instance of a configured group as
 *       an item with its depth and its nearest planned ancestor. Execution itself is the bulk verb
 *       {@code migrate-instance}; this class never calls {@code migrate}.
 * </ol>
 */
@Service
public class HierarchyPlanService {

    /** How an item the analysis blocked reads in the job: listed {@code not_run}, never sent. */
    public static final String NOT_SENT = "not sent — the analysis blocked this instance";

    private static final int REASON_MAX = 300;

    private final EngineRegistry registry;
    private final ProcessApiClient client;
    private final InstanceDetailService detail;
    private final CohortMigrationService cohorts;
    private final CohortAnalysisService analysisRuns;
    private final CohortBulkMigration cohortBulk;
    private final MigrationAnalysisRepository analyses;
    private final HierarchyPlanRepository plans;
    private final HierarchyPlanGroupRepository groups;
    private final RbacAuthorizer rbac;
    private final InspectorProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    public HierarchyPlanService(
            EngineRegistry registry,
            ProcessApiClient client,
            InstanceDetailService detail,
            CohortMigrationService cohorts,
            CohortAnalysisService analysisRuns,
            CohortBulkMigration cohortBulk,
            MigrationAnalysisRepository analyses,
            HierarchyPlanRepository plans,
            HierarchyPlanGroupRepository groups,
            RbacAuthorizer rbac,
            InspectorProperties props,
            ObjectMapper json,
            Clock clock) {
        this.registry = registry;
        this.client = client;
        this.detail = detail;
        this.cohorts = cohorts;
        this.analysisRuns = analysisRuns;
        this.cohortBulk = cohortBulk;
        this.analyses = analyses;
        this.plans = plans;
        this.groups = groups;
        this.rbac = rbac;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /** The stored tree. Counts in the view are derived from it and the group rows. */
    public record Payload(
            List<String> rootInstanceIds,
            List<PlanNode> nodes,
            List<PlanTruncation> truncations,
            List<PlanCircularReference> circularReferences,
            int maxDepth,
            int breadthCap,
            int nodeCap) {}

    /**
     * One instance of the job. {@code level} is its depth in the tree; {@code parentInstanceId}
     * the nearest ancestor that is also an item (a skipped group in between is transparent);
     * {@code notSentReason} is set when the analysis blocked the instance — it is listed, never
     * sent, and blocks its parent unless nothing is left of it to migrate (it is no longer
     * running, or already on its target).
     */
    public record Item(
            String instanceId, String analysisId, int level, String parentInstanceId, String notSentReason) {}

    /** A plan resolved for submit. {@code items} are ordered deepest level first. */
    public record Execution(
            UUID planId, String engineId, List<Item> items, int executableCount, Map<String, Object> provenance) {}

    /* --------------------------------- create --------------------------------- */

    public HierarchyPlanView create(
            String engineId, HierarchyPlanRequests.HierarchyPlanCreate request, Authentication auth) {
        requireAdmin(auth, engineId);
        EngineConfig engine = registry.require(engineId);
        String rootInstanceId = request == null ? null : blank(request.rootInstanceId());
        CohortPreviewRequest cohort = request == null ? null : request.cohort();
        if ((rootInstanceId == null) == (cohort == null)) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "hierarchy-root-required",
                    "Name exactly one root: rootInstanceId, or a cohort filter. Nothing happened.");
        }
        Caps caps = new Caps(
                props.hierarchyMaxDepthOrDefault(),
                Math.min(InstanceDetailService.HIERARCHY_BREADTH_CAP, engine.maxPageSizeOrDefault()),
                InstanceDetailService.HIERARCHY_MAX_RENDERED_NODES);
        Walk walk;
        try {
            List<Member> roots;
            long rootTotal;
            if (rootInstanceId != null) {
                Map<String, Object> instance =
                        client.getRuntimeProcessInstance(engine, CallPriority.INTERACTIVE, rootInstanceId);
                if (instance == null) {
                    throw new GuardRefusedException(
                            HttpStatus.NOT_FOUND,
                            "instance-not-running",
                            "Instance " + rootInstanceId + " is not running on '" + engineId
                                    + "' — a migration plan needs a running root. Nothing happened.");
                }
                roots = List.of(
                        new Member(rootInstanceId, str(instance, "processDefinitionId"), str(instance, "businessKey")));
                rootTotal = 1;
            } else {
                ListedCohort listed = cohorts.listInstances(engineId, cohort);
                roots = listed.instanceIds().stream()
                        .map(id -> new Member(id, listed.fromDefinitionId(), null))
                        .toList();
                rootTotal = listed.total();
            }
            walk = HierarchyTree.walk(roots, rootTotal, id -> page(detail.runningChildren(engine, id)), caps);
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "engine-unreachable",
                    "Engine '" + engineId + "' did not answer while the call-activity tree was read."
                            + " No plan was stored.");
        } catch (RestClientResponseException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "engine-read-failed",
                    "Engine '" + engineId + "' answered " + e.getStatusCode().value()
                            + " while the call-activity tree was read. No plan was stored.");
        }
        Payload payload = new Payload(
                walk.nodes().stream()
                        .filter(node -> node.depth() == 0)
                        .map(PlanNode::instanceId)
                        .toList(),
                walk.nodes(),
                walk.truncations(),
                walk.circularReferences(),
                caps.maxDepth(),
                caps.breadth(),
                caps.nodes());
        HierarchyPlan plan =
                new HierarchyPlan(UUID.randomUUID(), engineId, auth.getName(), clock.instant(), write(payload));
        plans.save(plan);
        return view(plan);
    }

    private static HierarchyTree.Page page(FlowablePage page) {
        if (page == null) {
            return new HierarchyTree.Page(List.of(), 0);
        }
        List<Member> rows = new ArrayList<>();
        for (Map<String, Object> row : page.dataOrEmpty()) {
            rows.add(new Member(str(row, "id"), str(row, "processDefinitionId"), str(row, "businessKey")));
        }
        return new HierarchyTree.Page(rows, page.total());
    }

    /* ---------------------------------- read ---------------------------------- */

    public HierarchyPlanView get(UUID id, Authentication auth) {
        HierarchyPlan plan = load(id);
        requireAdmin(auth, plan.getEngineId());
        return view(plan);
    }

    /* --------------------------------- decide --------------------------------- */

    public HierarchyPlanView decide(UUID id, HierarchyPlanRequests.HierarchyPlanDecide request, Authentication auth) {
        HierarchyPlan plan = load(id);
        requireAdmin(auth, plan.getEngineId());
        String definitionId = request == null ? null : blank(request.definitionId());
        List<String> members = definitionId == null
                ? List.of()
                : payload(plan).nodes().stream()
                        .filter(node -> definitionId.equals(node.definitionId()))
                        .map(PlanNode::instanceId)
                        .toList();
        if (members.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "hierarchy-group-unknown",
                    "Definition " + definitionId + " has no instance in plan " + id + ". Nothing happened.");
        }
        HierarchyPlanGroup row = groups.findById(new HierarchyPlanGroup.Key(id, definitionId))
                .orElseGet(() -> new HierarchyPlanGroup(id, definitionId));
        if (Boolean.TRUE.equals(request.skip())) {
            row.decide(Decision.SKIPPED, null, auth.getName(), clock.instant());
        } else {
            UUID analysisId = analysisRuns.startForInstances(
                    plan.getEngineId(), definitionId, request.toDefinitionId(), request.mappings(), members, auth);
            row.decide(Decision.CONFIGURED, analysisId, auth.getName(), clock.instant());
        }
        groups.save(row);
        return view(plan);
    }

    /* ------------------------------ submit resolution ------------------------------ */

    /** The plan's engine, for the caller's role check before anything about the plan is said. */
    public String engineOf(UUID id) {
        return load(id).getEngineId();
    }

    /**
     * Resolves the plan for the bulk door. Refuses a truncated plan, an undecided group, and
     * (through {@link CohortBulkMigration#resolve}) an analysis that is not completed.
     */
    public Execution execution(UUID id) {
        HierarchyPlan plan = load(id);
        Payload payload = payload(plan);
        if (!payload.truncations().isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "hierarchy-plan-truncated",
                    "Plan " + id + " does not hold the whole tree (" + truncationSummary(payload.truncations())
                            + "). Children-first cannot be kept over a tree that was not fully read. Migrate the"
                            + " wide level as a cohort, then plan again. Nothing happened.");
        }
        Map<String, HierarchyPlanGroup> decisions = decisions(id);
        Map<String, Cohort> resolved = new HashMap<>();
        Map<String, Map<String, String>> blockerReasons = new HashMap<>();
        List<Map<String, Object>> groupProvenance = new ArrayList<>();
        for (String definitionId : definitionIds(payload)) {
            HierarchyPlanGroup decision = decisions.get(definitionId);
            if (decision == null) {
                throw new GuardRefusedException(
                        HttpStatus.CONFLICT,
                        "hierarchy-plan-incomplete",
                        "Definition " + definitionId + " has no decision yet. Configure or skip every definition"
                                + " in the plan. Nothing happened.");
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("definitionId", definitionId);
            meta.put("decision", decision.getDecision().name());
            if (decision.getDecision() == Decision.CONFIGURED) {
                Cohort cohort = cohortBulk.resolve(decision.getAnalysisId());
                resolved.put(definitionId, cohort);
                blockerReasons.put(definitionId, blockerReasons(decision.getAnalysisId()));
                meta.put("analysisId", cohort.analysisId().toString());
                meta.put("toProcessDefinitionId", cohort.toDefinitionId());
                meta.put("executable", cohort.executableIds().size());
                meta.put("blockersNotSent", cohort.blockerCount());
            }
            groupProvenance.add(meta);
        }

        Map<String, PlanNode> byId = new HashMap<>();
        payload.nodes().forEach(node -> byId.put(node.instanceId(), node));
        Set<String> itemIds = payload.nodes().stream()
                .filter(node -> resolved.containsKey(node.definitionId()))
                .map(PlanNode::instanceId)
                .collect(Collectors.toSet());
        List<Item> items = new ArrayList<>();
        int executable = 0;
        for (PlanNode node : payload.nodes()) {
            Cohort cohort = resolved.get(node.definitionId());
            if (cohort == null) {
                continue; // skipped group: not in the job, transparent to the order
            }
            String notSent = null;
            if (!cohort.executableIds().contains(node.instanceId())) {
                String reason = blockerReasons.get(node.definitionId()).get(node.instanceId());
                notSent = NOT_SENT + (reason == null || reason.isBlank() ? "." : ": " + clip(reason));
            } else {
                executable++;
            }
            items.add(new Item(
                    node.instanceId(),
                    cohort.analysisId().toString(),
                    node.depth(),
                    plannedParent(node, byId, itemIds),
                    notSent));
        }
        if (executable == 0) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "hierarchy-nothing-executable",
                    "Plan " + id + " has nothing to send: every definition is skipped or blocked."
                            + " Nothing happened.");
        }
        items.sort(Comparator.comparingInt(Item::level).reversed());

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("hierarchyPlanId", id.toString());
        provenance.put("rootInstanceIds", payload.rootInstanceIds());
        provenance.put("order", "children-first");
        provenance.put(
                "levels", items.stream().map(Item::level).distinct().sorted().toList());
        provenance.put("groups", groupProvenance);
        provenance.put("circularReferences", payload.circularReferences());
        provenance.put("strategy", CohortMigrationService.STRATEGY);
        return new Execution(id, plan.getEngineId(), List.copyOf(items), executable, provenance);
    }

    /** The nearest ancestor that is itself an item of the job. */
    private static String plannedParent(PlanNode node, Map<String, PlanNode> byId, Set<String> itemIds) {
        String parent = node.parentInstanceId();
        while (parent != null && !itemIds.contains(parent)) {
            PlanNode up = byId.get(parent);
            parent = up == null ? null : up.parentInstanceId();
        }
        return parent;
    }

    /* ---------------------------------- view ---------------------------------- */

    private HierarchyPlanView view(HierarchyPlan plan) {
        Payload payload = payload(plan);
        Map<String, HierarchyPlanGroup> decisions = decisions(plan.getId());
        Map<UUID, MigrationAnalysis> analysisRows = new HashMap<>();
        analyses.findAllById(decisions.values().stream()
                        .map(HierarchyPlanGroup::getAnalysisId)
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .forEach(row -> analysisRows.put(row.getId(), row));

        Map<String, List<PlanNode>> byDefinition = new LinkedHashMap<>();
        payload.nodes()
                .forEach(node -> byDefinition
                        .computeIfAbsent(node.definitionId(), k -> new ArrayList<>())
                        .add(node));
        List<PlanGroupView> groupViews = new ArrayList<>();
        Set<String> sent = new HashSet<>();
        List<String> pending = new ArrayList<>();
        List<String> analyzing = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int notSent = 0;
        for (Map.Entry<String, List<PlanNode>> entry : byDefinition.entrySet()) {
            String definitionId = entry.getKey();
            List<PlanNode> members = entry.getValue();
            HierarchyPlanGroup decision = decisions.get(definitionId);
            MigrationAnalysis analysis = decision == null ? null : analysisRows.get(decision.getAnalysisId());
            String status;
            int executable = 0;
            int blockers = 0;
            if (decision == null) {
                status = "PENDING";
                pending.add(label(definitionId));
            } else if (decision.getDecision() == Decision.SKIPPED) {
                status = "SKIPPED";
            } else if (analysis == null || analysis.getStatus() == Status.FAILED) {
                status = "ANALYSIS_FAILED";
                failed.add(label(definitionId));
            } else if (analysis.getStatus() == Status.RUNNING) {
                status = "ANALYZING";
                analyzing.add(label(definitionId));
            } else {
                status = "CONFIGURED";
                CohortAnalysisService.Payload result = read(analysis.getPayload(), CohortAnalysisService.Payload.class);
                Set<String> ids = new HashSet<>(result.executableIds() == null ? List.of() : result.executableIds());
                for (PlanNode node : members) {
                    if (ids.contains(node.instanceId())) {
                        sent.add(node.instanceId());
                        executable++;
                    } else {
                        blockers++;
                    }
                }
                notSent += blockers;
            }
            groupViews.add(new PlanGroupView(
                    definitionId,
                    HierarchyTree.keyOf(definitionId),
                    HierarchyTree.versionOf(definitionId),
                    members.size(),
                    members.stream().mapToInt(PlanNode::depth).min().orElse(0),
                    members.stream().mapToInt(PlanNode::depth).max().orElse(0),
                    status,
                    decision == null ? null : decision.getAnalysisId(),
                    analysis == null ? null : analysis.getTargetDefinitionId(),
                    executable,
                    blockers));
        }

        TreeMap<Integer, int[]> perDepth = new TreeMap<>();
        for (PlanNode node : payload.nodes()) {
            int[] counts = perDepth.computeIfAbsent(node.depth(), k -> new int[2]);
            counts[0]++;
            if (sent.contains(node.instanceId())) {
                counts[1]++;
            }
        }
        List<PlanLevelView> levels = new ArrayList<>();
        perDepth.forEach((depth, counts) -> levels.add(new PlanLevelView(depth, counts[0], counts[1])));

        String reason = null;
        if (!payload.truncations().isEmpty()) {
            reason = "The plan does not hold the whole tree (" + truncationSummary(payload.truncations())
                    + "). Migrate the wide level as a cohort, then plan again.";
        } else if (!pending.isEmpty()) {
            reason = "Still to decide: " + String.join(", ", pending) + ".";
        } else if (!failed.isEmpty()) {
            reason = "The estimate failed for " + String.join(", ", failed) + ". Configure it again.";
        } else if (!analyzing.isEmpty()) {
            reason = "The estimate is still running for " + String.join(", ", analyzing) + ".";
        } else if (sent.isEmpty()) {
            reason = "Nothing to send: every definition is skipped or blocked.";
        }
        return new HierarchyPlanView(
                plan.getId(),
                plan.getEngineId(),
                plan.getCreatedBy(),
                plan.getCreatedAt(),
                payload.rootInstanceIds(),
                payload.nodes(),
                List.copyOf(groupViews),
                List.copyOf(levels),
                payload.truncations(),
                payload.circularReferences(),
                payload.maxDepth(),
                payload.breadthCap(),
                payload.nodeCap(),
                sent.size(),
                notSent,
                reason == null,
                reason,
                HierarchyPlanView.BANNER);
    }

    /* --------------------------------- helpers --------------------------------- */

    private HierarchyPlan load(UUID id) {
        return plans.findById(id)
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.NOT_FOUND, "hierarchy-plan-not-found", "No hierarchy migration plan " + id + "."));
    }

    private Map<String, HierarchyPlanGroup> decisions(UUID planId) {
        Map<String, HierarchyPlanGroup> byDefinition = new HashMap<>();
        groups.findByPlanId(planId).forEach(row -> byDefinition.put(row.getDefinitionId(), row));
        return byDefinition;
    }

    private static List<String> definitionIds(Payload payload) {
        return payload.nodes().stream().map(PlanNode::definitionId).distinct().toList();
    }

    private Map<String, String> blockerReasons(UUID analysisId) {
        Map<String, String> reasons = new HashMap<>();
        analyses.findById(analysisId).ifPresent(row -> {
            CohortAnalysisService.Payload result = read(row.getPayload(), CohortAnalysisService.Payload.class);
            for (BlockedInstance blocked : result.blockers() == null ? List.<BlockedInstance>of() : result.blockers()) {
                reasons.put(blocked.instanceId(), blocked.reason());
            }
        });
        return reasons;
    }

    private static String truncationSummary(List<PlanTruncation> truncations) {
        return truncations.stream()
                .map(t -> switch (t.kind()) {
                    case "breadth" ->
                        t.instanceId() + " has " + t.total() + " running children, " + t.enumerated() + " read";
                    case "depth" -> "children exist below the depth cap at " + t.instanceId();
                    case "nodes" -> "the node cap was reached at " + t.instanceId();
                    case "roots" -> "the root cohort has " + t.total() + " instances, " + t.enumerated() + " read";
                    default -> "instance " + t.instanceId() + " appears under two parents";
                })
                .limit(3)
                .collect(Collectors.joining("; "));
    }

    private static String label(String definitionId) {
        Integer version = HierarchyTree.versionOf(definitionId);
        return HierarchyTree.keyOf(definitionId) + (version == null ? "" : " v" + version);
    }

    private static String clip(String value) {
        return value.length() > REASON_MAX ? value.substring(0, REASON_MAX) + "…" : value;
    }

    private void requireAdmin(Authentication auth, String engineId) {
        if (auth == null || !rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN, "rbac-denied", "A hierarchy migration plan requires ADMIN on this engine.");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Payload payload(HierarchyPlan plan) {
        return read(plan.getPayload(), Payload.class);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("hierarchy plan could not be stored", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("hierarchy plan could not be read", e);
        }
    }
}
