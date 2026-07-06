package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.action.ActionRequest;
import io.inspector.action.ActionResult;
import io.inspector.action.ActionVerb;
import io.inspector.action.CorrectiveActionService;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.ResourceAccessException;

/**
 * Rung 1 for the SPEC §7 bulk rails: submit-time guards (cap, verb whitelist, protected
 * auto-exclusion), per-item outcome mapping (skipped vs failed vs unknown — partial
 * failure is a NORMAL outcome), retry's dispatch-time DLQ resolution (the built-in
 * precondition recheck), verify-now reclassification, and the startup INTERRUPTED sweep.
 * Engine wire truth is rung 4; the single-target service is mocked as OUR proven seam.
 */
class BulkJobServiceTest {

    private static final String ENGINE = "engine-a";

    private final BulkJobRepository jobs = mock(BulkJobRepository.class);
    private final BulkJobItemRepository items = mock(BulkJobItemRepository.class);
    private final CorrectiveActionService actions = mock(CorrectiveActionService.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final FlowableEngineClient client = mock(FlowableEngineClient.class);
    private final Authentication responder = new TestingAuthenticationToken("resp", "n/a", "ROLE_RESPONDER");

    private final Map<UUID, BulkJob> jobStore = new ConcurrentHashMap<>();
    private final Map<String, BulkJobItem> itemStore = new ConcurrentHashMap<>();

    private BulkJobService service;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                List.of(TestEngines.engine(
                        ENGINE, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));

        // In-memory repo fakes: the service's persistence calls hit these maps, so the
        // async run() can be awaited on observable state (no Thread.sleep — Awaitility).
        when(jobs.saveAndFlush(any())).thenAnswer(inv -> {
            BulkJob job = inv.getArgument(0);
            jobStore.put(job.getId(), job);
            return job;
        });
        when(jobs.findById(any())).thenAnswer(inv -> Optional.ofNullable(jobStore.get(inv.<UUID>getArgument(0))));
        when(jobs.findByStateIn(any())).thenAnswer(inv -> {
            List<BulkJob.State> states = inv.getArgument(0);
            return jobStore.values().stream()
                    .filter(j -> states.contains(j.getState()))
                    .toList();
        });
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
                    .sorted(java.util.Comparator.comparingInt(BulkJobItem::getOrdinal))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        });
        when(items.findById(any())).thenAnswer(inv -> {
            BulkJobItem.Key key = inv.getArgument(0);
            return itemStore.values().stream()
                    .filter(item -> item.getJobId().equals(keyJob(key)) && item.getOrdinal() == keyOrdinal(key))
                    .findFirst();
        });

        when(protectedInstances.findAllById(any())).thenReturn(List.of());
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(envelope());

        service = new BulkJobService(
                jobs,
                items,
                actions,
                protectedInstances,
                audit,
                registry,
                client,
                Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC));
    }

    private static UUID keyJob(BulkJobItem.Key key) {
        // Key has no getters; identity comparison via reflection-free equals trick.
        try {
            var field = BulkJobItem.Key.class.getDeclaredField("jobId");
            field.setAccessible(true);
            return (UUID) field.get(key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int keyOrdinal(BulkJobItem.Key key) {
        try {
            var field = BulkJobItem.Key.class.getDeclaredField("ordinal");
            field.setAccessible(true);
            return (int) field.get(key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AuditEntry envelope() {
        return new AuditEntry(
                UUID.randomUUID(),
                "corr-bulk",
                "resp",
                Instant.parse("2026-07-06T12:00:00Z"),
                ENGINE,
                null,
                null,
                "bulk:suspend",
                null,
                null,
                null,
                false);
    }

    private static BulkDtos.BulkSubmitRequest suspendOf(String... instanceIds) {
        List<BulkDtos.BulkTarget> targets = new ArrayList<>();
        for (String id : instanceIds) {
            targets.add(new BulkDtos.BulkTarget(ENGINE, id, null));
        }
        return new BulkDtos.BulkSubmitRequest("suspend", null, null, null, targets);
    }

    private BulkDtos.BulkJobDto awaitFinished(UUID id) {
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> jobStore.get(id) != null
                        && jobStore.get(id).getState() != BulkJob.State.PENDING
                        && jobStore.get(id).getState() != BulkJob.State.RUNNING);
        return service.get(id);
    }

    /* ---------------- submit-time guards ---------------- */

    @Test
    void refusesVerbsOutsideTheBulkWhitelist() {
        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkSubmitRequest(
                                "terminate-delete",
                                "long enough reason",
                                null,
                                null,
                                List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
                        responder))
                .isInstanceOf(GuardRefusedException.class)
                .hasMessageContaining("tier-4");
        verifyNoInteractions(audit);
        assertThat(jobStore).isEmpty();
    }

    @Test
    void refusesOverTheItemCap() {
        String[] ids = new String[201];
        for (int i = 0; i < 201; i++) ids[i] = "pi-" + i;
        assertThatThrownBy(() -> service.submit(suspendOf(ids), responder))
                .isInstanceOf(GuardRefusedException.class)
                .hasMessageContaining("200");
        assertThat(jobStore).isEmpty();
    }

    @Test
    void refusesEmptySelectionAndShortReason() {
        assertThatThrownBy(() -> service.submit(suspendOf(), responder))
                .isInstanceOf(GuardRefusedException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkSubmitRequest(
                                "suspend", "short", null, null, List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
                        responder))
                .isInstanceOf(GuardRefusedException.class)
                .hasMessageContaining("10 characters");
    }

    @Test
    void protectedInstancesAreSettledAtSubmitAndNeverDispatched() {
        when(protectedInstances.findAllById(any()))
                .thenReturn(List.of(new ProtectedInstance(ENGINE, "pi-2", "regulatory hold", "admin", Instant.EPOCH)));
        when(actions.execute(any(), any(), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1", "pi-2", "pi-3"), responder);
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());

        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.items().get(1).state()).isEqualTo("skipped_protected");
        assertThat(done.tallies()).containsEntry("ok", 2L).containsEntry("skipped_protected", 1L);
        verify(actions).execute(eq(ENGINE), eq("pi-1"), eq(ActionVerb.SUSPEND), any(), eq(responder));
        verify(actions).execute(eq(ENGINE), eq("pi-3"), eq(ActionVerb.SUSPEND), any(), eq(responder));
    }

    /* ---------------- per-item outcome mapping (SPEC §7 classes) ---------------- */

    @Test
    void mapsGuardEngineAndTimeoutFailuresToTheirOutcomeClasses() {
        when(actions.execute(eq(ENGINE), eq("pi-gone"), any(), any(), any()))
                .thenThrow(
                        new GuardRefusedException(HttpStatus.NOT_FOUND, "instance-not-running", "already completed"));
        when(actions.execute(eq(ENGINE), eq("pi-rejected"), any(), any(), any()))
                .thenThrow(new EngineRejectedException(UUID.randomUUID(), 409, "already suspended"));
        when(actions.execute(eq(ENGINE), eq("pi-timeout"), any(), any(), any()))
                .thenThrow(new OutcomeUnknownException(
                        UUID.randomUUID(),
                        new ResourceAccessException("t/o", new HttpTimeoutException("request timed out"))));
        when(actions.execute(eq(ENGINE), eq("pi-ok"), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));

        BulkDtos.BulkJobDto submitted =
                service.submit(suspendOf("pi-gone", "pi-rejected", "pi-timeout", "pi-ok"), responder);
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());

        assertThat(done.state()).isEqualTo("COMPLETED"); // partial failure is a NORMAL outcome
        assertThat(done.items())
                .extracting(BulkDtos.BulkItemDto::state)
                .containsExactly("skipped", "failed", "unknown", "ok");
        assertThat(done.items().get(2).detail()).contains("Verify now");
    }

    /* ---------------- retry: dispatch-time DLQ resolution ---------------- */

    @Test
    void retryResolvesCurrentDeadLetterJobsPerInstance() {
        when(client.listJobs(any(), eq(JobLaneKind.DEADLETTER), anyMap(), anyInt(), anyInt()))
                .thenReturn(page(List.of(Map.of("id", "dlq-1"), Map.of("id", "dlq-2"))));
        when(actions.execute(any(), any(), eq(ActionVerb.RETRY_JOB), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "moved"));

        BulkDtos.BulkJobDto submitted = service.submit(
                new BulkDtos.BulkSubmitRequest(
                        "retry-job", null, null, null, List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
                responder);
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());

        assertThat(done.items().get(0).state()).isEqualTo("ok");
        assertThat(done.items().get(0).detail()).contains("2 dead-letter jobs");
        verify(actions).execute(eq(ENGINE), eq("pi-1"), eq(ActionVerb.RETRY_JOB), argJob("dlq-1"), eq(responder));
        verify(actions).execute(eq(ENGINE), eq("pi-1"), eq(ActionVerb.RETRY_JOB), argJob("dlq-2"), eq(responder));
    }

    @Test
    void retryWithNoDeadLettersLeftSkipsAsAlreadyResolved() {
        when(client.listJobs(any(), eq(JobLaneKind.DEADLETTER), anyMap(), anyInt(), anyInt()))
                .thenReturn(page(List.of()));

        BulkDtos.BulkJobDto submitted = service.submit(
                new BulkDtos.BulkSubmitRequest(
                        "retry-job", null, null, null, List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
                responder);
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());

        assertThat(done.items().get(0).state()).isEqualTo("skipped");
        verifyNoInteractions(actions);
    }

    private static ActionRequest argJob(String jobId) {
        return org.mockito.ArgumentMatchers.argThat(req -> req != null && jobId.equals(req.jobId()));
    }

    private static FlowablePage page(List<Map<String, Object>> data) {
        return new FlowablePage(data, data.size(), 0, data.size());
    }

    /* ---------------- verify-now (R-SAFE-09) ---------------- */

    @Test
    void verifyNowReclassifiesAVanishedDeadLetterAsOk() {
        BulkJob job = new BulkJob(UUID.randomUUID(), "resp", Instant.EPOCH, "retry-job", null, null, 1, null);
        job.finish(BulkJob.State.COMPLETED, Instant.EPOCH);
        jobStore.put(job.getId(), job);
        BulkJobItem item = new BulkJobItem(job.getId(), 0, ENGINE, "pi-1", "dlq-1", BulkJobItem.State.pending);
        item.settle(BulkJobItem.State.unknown, "timeout", null, Instant.EPOCH);
        itemStore.put(job.getId() + "#0", item);
        when(client.getJob(any(), eq(JobLaneKind.DEADLETTER), eq("dlq-1"))).thenReturn(null);

        BulkDtos.BulkItemDto verified = service.verifyNow(job.getId(), 0);

        assertThat(verified.state()).isEqualTo("ok");
        assertThat(verified.detail()).contains("no longer dead-lettered");
    }

    @Test
    void verifyNowKeepsAStillQueuedItemUnknownWithEvidence() {
        BulkJob job = new BulkJob(UUID.randomUUID(), "resp", Instant.EPOCH, "retry-job", null, null, 1, null);
        job.finish(BulkJob.State.COMPLETED, Instant.EPOCH);
        jobStore.put(job.getId(), job);
        BulkJobItem item = new BulkJobItem(job.getId(), 0, ENGINE, "pi-1", "dlq-1", BulkJobItem.State.pending);
        item.settle(BulkJobItem.State.unknown, "timeout", null, Instant.EPOCH);
        itemStore.put(job.getId() + "#0", item);
        when(client.getJob(any(), eq(JobLaneKind.DEADLETTER), eq("dlq-1"))).thenReturn(Map.of("id", "dlq-1"));

        BulkDtos.BulkItemDto verified = service.verifyNow(job.getId(), 0);

        assertThat(verified.state()).isEqualTo("unknown");
        assertThat(verified.detail()).contains("STILL dead-lettered");
    }

    @Test
    void verifyNowRefusesNonUnknownItems() {
        BulkJob job = new BulkJob(UUID.randomUUID(), "resp", Instant.EPOCH, "retry-job", null, null, 1, null);
        jobStore.put(job.getId(), job);
        BulkJobItem item = new BulkJobItem(job.getId(), 0, ENGINE, "pi-1", "dlq-1", BulkJobItem.State.pending);
        item.settle(BulkJobItem.State.ok, "done", null, Instant.EPOCH);
        itemStore.put(job.getId() + "#0", item);

        assertThatThrownBy(() -> service.verifyNow(job.getId(), 0))
                .isInstanceOf(GuardRefusedException.class)
                .hasMessageContaining("unknown outcomes only");
    }

    /* ---------------- startup reconciliation (SPEC §7: no automatic resume) ---------------- */

    @Test
    void reconciliationSweepsRunningJobsToInterrupted() {
        BulkJob job = new BulkJob(UUID.randomUUID(), "resp", Instant.EPOCH, "suspend", null, null, 3, null);
        job.markRunning();
        jobStore.put(job.getId(), job);
        BulkJobItem done = new BulkJobItem(job.getId(), 0, ENGINE, "pi-0", null, BulkJobItem.State.pending);
        done.settle(BulkJobItem.State.ok, "suspended", null, Instant.EPOCH);
        BulkJobItem inFlight = new BulkJobItem(job.getId(), 1, ENGINE, "pi-1", null, BulkJobItem.State.pending);
        inFlight.markDispatched();
        BulkJobItem waiting = new BulkJobItem(job.getId(), 2, ENGINE, "pi-2", null, BulkJobItem.State.pending);
        itemStore.put(job.getId() + "#0", done);
        itemStore.put(job.getId() + "#1", inFlight);
        itemStore.put(job.getId() + "#2", waiting);

        service.reconcileInterrupted();

        assertThat(jobStore.get(job.getId()).getState()).isEqualTo(BulkJob.State.INTERRUPTED);
        assertThat(done.getState()).isEqualTo(BulkJobItem.State.ok); // settled outcomes untouched
        assertThat(inFlight.getState()).isEqualTo(BulkJobItem.State.unknown); // never re-fired
        assertThat(waiting.getState()).isEqualTo(BulkJobItem.State.not_run);
    }
}
