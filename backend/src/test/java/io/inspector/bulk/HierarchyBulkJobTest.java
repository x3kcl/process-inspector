package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.ActionResult;
import io.inspector.action.CorrectiveActionService;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.GuardedCaller;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.migration.CohortBulkMigration;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.OidcProperties;
import io.inspector.security.reauth.DangerousActionReauthGate;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.ResourceAccessException;

/**
 * Rung 1 for hierarchy-aware migration (#497): the bulk runner dispatches the deepest level first
 * and never sends a parent whose child did not reach its target. The migrate call itself is the
 * mocked {@link CohortBulkMigration} seam; the real engine order is rung 4
 * ({@code HierarchyMigrationIT}).
 */
class HierarchyBulkJobTest {

    private static final String ENGINE = "engine-a";
    private static final String ANALYSIS = "7d3c6a0e-0000-4000-8000-000000000497";
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

    private final BulkJobRepository jobs = mock(BulkJobRepository.class);
    private final BulkJobItemRepository items = mock(BulkJobItemRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final CohortBulkMigration cohortMigration = mock(CohortBulkMigration.class);
    private final Authentication admin = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");

    private final Map<UUID, BulkJob> jobStore = new ConcurrentHashMap<>();
    private final Map<String, BulkJobItem> itemStore = new ConcurrentHashMap<>();
    /** Instance ids in the order the migrate seam was called. */
    private final List<String> dispatched = new CopyOnWriteArrayList<>();
    /** What each instance's planned children looked like at the moment it was dispatched. */
    private final Map<String, List<String>> childStatesAtDispatch = new ConcurrentHashMap<>();

    private BulkJobService service;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(TestEngines.engine(
                        ENGINE, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));
        when(jobs.saveAndFlush(any())).thenAnswer(inv -> {
            BulkJob job = inv.getArgument(0);
            jobStore.put(job.getId(), job);
            return job;
        });
        when(jobs.findById(any())).thenAnswer(inv -> Optional.ofNullable(jobStore.get(inv.<UUID>getArgument(0))));
        when(items.saveAndFlush(any())).thenAnswer(inv -> {
            BulkJobItem item = inv.getArgument(0);
            itemStore.put(item.getJobId() + "#" + item.getOrdinal(), item);
            return item;
        });
        when(items.saveAllAndFlush(any())).thenAnswer(inv -> {
            List<BulkJobItem> list = inv.getArgument(0);
            list.forEach(item -> itemStore.put(item.getJobId() + "#" + item.getOrdinal(), item));
            return list;
        });
        when(items.findByJobIdOrderByOrdinal(any())).thenAnswer(inv -> {
            UUID jobId = inv.getArgument(0);
            return itemStore.values().stream()
                    .filter(item -> item.getJobId().equals(jobId))
                    .sorted(Comparator.comparingInt(BulkJobItem::getOrdinal))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        });
        ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
        ProtectedDefinitionRepository protectedDefinitions = mock(ProtectedDefinitionRepository.class);
        when(protectedInstances.findAllById(any())).thenReturn(List.of());
        when(protectedDefinitions.findAllById(any())).thenReturn(List.of());
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AuditEntry(
                        UUID.randomUUID(),
                        "corr-plan",
                        "admin",
                        NOW,
                        ENGINE,
                        null,
                        null,
                        "bulk:migrate-instance",
                        null,
                        null,
                        null,
                        false));
        when(cohortMigration.verify(any(), any(), any()))
                .thenReturn(new CohortBulkMigration.Verification(false, "instance is still on the source"));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new BulkJobService(
                jobs,
                items,
                mock(CorrectiveActionService.class),
                protectedInstances,
                protectedDefinitions,
                audit,
                registry,
                mock(ProcessApiClient.class),
                clock,
                new InspectorProperties(null, null, null, new InspectorProperties.Bulk(4, 0, 0, 0), List.of()),
                event -> {},
                new DangerousActionReauthGate(new OidcProperties(null, false, null), clock),
                mock(GuardedCaller.class),
                new SimpleMeterRegistry(),
                cohortMigration);
    }

    /** One planned instance: {@code parent} is its planned parent, null for the top. */
    private record Planned(String id, int level, String parent, String notSent) {}

    private BulkDtos.BulkJobDto run(Planned... plan) {
        List<BulkDtos.BulkTarget> targets = new ArrayList<>();
        Map<String, HierarchyOrder.Placement> placements = new HashMap<>();
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (Planned planned : plan) {
            targets.add(new BulkDtos.BulkTarget(ENGINE, planned.id(), ANALYSIS));
            placements.put(
                    HierarchyOrder.key(ENGINE, planned.id()),
                    new HierarchyOrder.Placement(planned.level(), planned.parent(), planned.notSent()));
            if (planned.parent() != null) {
                childrenOf
                        .computeIfAbsent(planned.parent(), k -> new ArrayList<>())
                        .add(planned.id());
            }
        }
        // Every instance not stubbed to fail migrates; each call records the order and what its
        // children looked like when it was sent.
        org.mockito.Mockito.lenient()
                .when(cohortMigration.dispatch(eq(ANALYSIS), eq(ENGINE), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(2);
                    dispatched.add(id);
                    childStatesAtDispatch.put(
                            id,
                            childrenOf.getOrDefault(id, List.of()).stream()
                                    .map(child -> itemStore.values().stream()
                                            .filter(item -> item.getInstanceId().equals(child))
                                            .findFirst()
                                            .orElseThrow()
                                            .getState()
                                            .name())
                                    .toList());
                    RuntimeException failure = failures.get(id);
                    if (failure != null) {
                        throw failure;
                    }
                    return new ActionResult(UUID.randomUUID(), "corr-plan", "ok", 200, "migrated " + id);
                });
        BulkDtos.BulkJobDto submitted = service.submitMigration(
                new BulkDtos.BulkSubmitRequest(
                        "migrate-instance", "hierarchy move off the bad deploy", null, null, targets),
                admin,
                Map.of(),
                "hierarchy migrate",
                placements);
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> jobStore.get(submitted.id()) != null
                        && jobStore.get(submitted.id()).getState() != BulkJob.State.PENDING
                        && jobStore.get(submitted.id()).getState() != BulkJob.State.RUNNING);
        return service.get(submitted.id(), null);
    }

    private final Map<String, RuntimeException> failures = new HashMap<>();

    private static BulkDtos.BulkItemDto item(BulkDtos.BulkJobDto job, String id) {
        return job.items().stream()
                .filter(i -> i.instanceId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void threeLevelsRunDeepestFirstEachParentAfterItsChildSettled() {
        // Submitted deepest first, as the plan resolves it.
        BulkDtos.BulkJobDto done = run(
                new Planned("b", 2, "a", null), new Planned("a", 1, "root", null), new Planned("root", 0, null, null));

        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.items()).extracting(BulkDtos.BulkItemDto::state).containsOnly("ok");
        assertThat(dispatched).containsExactly("b", "a", "root");
        assertThat(childStatesAtDispatch).containsEntry("a", List.of("ok")).containsEntry("root", List.of("ok"));
        assertThat(item(done, "b").level()).isEqualTo(2);
        assertThat(item(done, "b").parentInstanceId()).isEqualTo("a");
        assertThat(item(done, "root").parentInstanceId()).isNull();
    }

    @Test
    void aFailedChildLeavesItsParentChainNotRunWithTheNamedCauseAndSiblingSubtreesContinue() {
        failures.put("b", new EngineRejectedException(UUID.randomUUID(), 500, "no mapping for approveB"));

        BulkDtos.BulkJobDto done = run(
                new Planned("b", 2, "a", null),
                new Planned("b2", 2, "a2", null),
                new Planned("a", 1, "root", null),
                new Planned("a2", 1, "root", null),
                new Planned("root", 0, null, null));

        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(item(done, "b").state()).isEqualTo("failed");
        assertThat(item(done, "a").state()).isEqualTo("not_run");
        assertThat(item(done, "a").detail())
                .contains("blocked by child engine-a:b")
                .contains("failed")
                .contains("no mapping for approveB");
        assertThat(item(done, "root").state()).isEqualTo("not_run");
        assertThat(item(done, "root").detail())
                .contains("blocked by child engine-a:a")
                .contains("not_run");
        // The sibling subtree is independent: b2 and a2 both moved.
        assertThat(item(done, "b2").state()).isEqualTo("ok");
        assertThat(item(done, "a2").state()).isEqualTo("ok");
        assertThat(dispatched).doesNotContain("a", "root");
        verify(cohortMigration, never()).dispatch(any(), any(), eq("a"), any(), any(), any());
        verify(cohortMigration, never()).dispatch(any(), any(), eq("root"), any(), any(), any());
    }

    @Test
    void anUnknownChildBlocksItsParentAndIsNeverReFired() {
        failures.put(
                "b",
                new OutcomeUnknownException(
                        UUID.randomUUID(),
                        new ResourceAccessException("t/o", new HttpTimeoutException("request timed out"))));

        BulkDtos.BulkJobDto done = run(new Planned("b", 2, "a", null), new Planned("a", 1, null, null));

        assertThat(item(done, "b").state()).isEqualTo("unknown");
        assertThat(item(done, "a").state()).isEqualTo("not_run");
        assertThat(item(done, "a").detail())
                .contains("blocked by child engine-a:b")
                .contains("unknown");
        assertThat(dispatched).containsExactly("b");
    }

    @Test
    void aFailedParentNeverTouchesItsMigratedChildren() {
        failures.put("a", new EngineRejectedException(UUID.randomUUID(), 500, "boom"));

        BulkDtos.BulkJobDto done = run(new Planned("b", 2, "a", null), new Planned("a", 1, null, null));

        assertThat(item(done, "b").state()).isEqualTo("ok");
        assertThat(item(done, "a").state()).isEqualTo("failed");
    }

    @Test
    void aChildTheAnalysisBlockedIsListedNotRunNeverSentAndBlocksItsParent() {
        BulkDtos.BulkJobDto done = run(
                new Planned("b", 2, "a", "not sent — the analysis blocked this instance: unmapped reviewB"),
                new Planned("a", 1, null, null));

        assertThat(item(done, "b").state()).isEqualTo("not_run");
        assertThat(item(done, "b").detail()).contains("unmapped reviewB");
        assertThat(item(done, "a").state()).isEqualTo("not_run");
        assertThat(item(done, "a").detail()).contains("blocked by child engine-a:b");
        assertThat(dispatched).isEmpty();
    }
}
