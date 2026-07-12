package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.OidcProperties;
import io.inspector.security.reauth.DangerousActionReauthGate;
import io.inspector.security.reauth.ReauthRequiredException;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
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
    /** The suite's fixed clock instant — the reauth gate shares it so freshness cases are deterministic. */
    private static final Instant NOW = Instant.parse("2026-07-06T12:00:00Z");

    private final BulkJobRepository jobs = mock(BulkJobRepository.class);
    private final BulkJobItemRepository items = mock(BulkJobItemRepository.class);
    private final CorrectiveActionService actions = mock(CorrectiveActionService.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final ProcessApiClient client = mock(ProcessApiClient.class);
    private final GuardedCaller guardedCaller = mock(GuardedCaller.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
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

        // circuit-pause bound 0 in rung 1 (mirrors "stagger 0") — the bounded-wait-and-retry
        // behavior itself is asserted separately, with tests that set an explicit bound.
        when(guardedCaller.isOpen(any(), any())).thenReturn(false);
        service = new BulkJobService(
                jobs,
                items,
                actions,
                protectedInstances,
                audit,
                registry,
                client,
                Clock.fixed(NOW, ZoneOffset.UTC),
                // stagger 0 in rung 1 — pacing behavior itself is asserted separately
                new InspectorProperties(null, null, null, new InspectorProperties.Bulk(4, 0, 0, 0), List.of()),
                event -> {},
                reauthGate(),
                guardedCaller,
                metrics);
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
        return new BulkDtos.BulkSubmitRequest("suspend", "ops-4711 incident", null, null, targets);
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

    /* ---------------- dangerous-set re-auth at SUBMIT (R-SAFE-07, IDP-SECURITY.md §5) ---------------- */

    /** The gate the service is built with — shares the suite's fixed clock, default 15-min window. */
    private static DangerousActionReauthGate reauthGate() {
        return new DangerousActionReauthGate(new OidcProperties(null, false, null), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** An OIDC session whose auth_time is {@code age} old, holding RESPONDER (the bulk door floor). */
    private static Authentication oidcSession(Duration age) {
        Map<String, Object> claims =
                Map.of("sub", "u-1", "auth_time", NOW.minus(age).getEpochSecond());
        var idToken = new org.springframework.security.oauth2.core.oidc.OidcIdToken(
                "id-tok", NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), claims);
        var authorities = List.<org.springframework.security.core.GrantedAuthority>of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_RESPONDER"));
        return new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(authorities, idToken),
                authorities,
                "oidc");
    }

    @Test
    void aStaleOidcSessionIsChallengedAtSubmitBeforeAnythingIsPersistedOrAudited() {
        // Bulk is in the dangerous set REGARDLESS of verb tier (guard-tier-4 fan-out): even the
        // tier-0 suspend fan-out challenges a 20-min-old session. Nothing persisted, nothing audited.
        assertThatThrownBy(() -> service.submit(suspendOf("pi-1"), oidcSession(Duration.ofMinutes(20))))
                .isInstanceOf(ReauthRequiredException.class);
        verifyNoInteractions(audit);
        verifyNoInteractions(actions);
        assertThat(jobStore).isEmpty();
    }

    @Test
    void aFreshOidcSessionSubmitsNormallyAndIsNeverReChallengedPerItem() {
        // Within-window session → the challenge passes ONCE at submit; the per-item workers then run
        // the whole fan-out with no further freshness check (a bulk job survives its session,
        // R-SEM-10 — the per-item rails are RBAC + audit, not re-auth).
        when(actions.execute(any(), any(), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "corr", "ok", 200, "done"));

        var dto = service.submit(suspendOf("pi-1", "pi-2"), oidcSession(Duration.ofMinutes(5)));
        var done = awaitFinished(dto.id());

        assertThat(done.state()).isEqualTo("COMPLETED");
        verify(actions, org.mockito.Mockito.times(2)).execute(any(), any(), eq(ActionVerb.SUSPEND), any(), any());
    }

    /**
     * C-back (usability fix): the ticked-row door used to length-check the reason only when
     * present; unified with the error-class/filter siblings — null AND blank now refuse the
     * same way a too-short reason does.
     */
    @Test
    void refusesMissingOrBlankReasonLikeTheStrictSiblings() {
        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkSubmitRequest(
                                "suspend", null, null, null, List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
                        responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("reason-too-short");
                    assertThat(e.getMessage()).contains("10 characters");
                });
        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkSubmitRequest(
                                "suspend", "   ", null, null, List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
                        responder))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("reason-too-short"));
        assertThat(jobStore).isEmpty();
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

    @Test
    void circuitOpenMidBulkPausesDispatchInsteadOfBurningTheRestAsFailures() {
        // permits=1, stagger=0 ⇒ strict ordinal dispatch, one item at a time (deterministic).
        service = serviceWith(1, 0, new java.util.concurrent.CopyOnWriteArrayList<>());
        when(actions.execute(eq(ENGINE), eq("pi-1"), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));
        // The breaker trips on pi-2: the BFF's guarded chokepoint fast-fails BEFORE any bytes
        // leave — CorrectiveActionService surfaces it as this shedding-load guard refusal.
        when(actions.execute(eq(ENGINE), eq("pi-2"), any(), any(), any()))
                .thenThrow(new GuardRefusedException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "engine-shedding-load",
                        "Engine 'engine-a' is shedding load (circuit open) — the action was not sent."));
        // pi-3 and pi-4 are deliberately UNSTUBBED: the pause must keep them from ever being
        // dispatched (a stub that never fires would also fail Mockito's strict-stub check).

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1", "pi-2", "pi-3", "pi-4"), responder);
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());

        // Partial run: dispatch PAUSED, the job is INTERRUPTED (offer "continue as new job"),
        // never COMPLETED — undispatched work is surfaced, not silently dropped.
        assertThat(done.state()).isEqualTo("INTERRUPTED");
        assertThat(done.items())
                .extracting(BulkDtos.BulkItemDto::state)
                .containsExactly("ok", "failed", "not_run", "not_run");
        assertThat(done.tallies())
                .containsEntry("ok", 1L)
                .containsEntry("failed", 1L)
                .containsEntry("not_run", 2L);
        // The undispatched items were NEVER sent to the engine — do-no-harm held.
        verify(actions, never()).execute(eq(ENGINE), eq("pi-3"), any(), any(), any());
        verify(actions, never()).execute(eq(ENGINE), eq("pi-4"), any(), any(), any());
        // The fast-failed item carries the clean shedding-load reason, not an "unexpected" crash.
        assertThat(done.items().get(1).detail()).contains("shedding load");
        assertThat(done.items().get(2).detail()).contains("paused");
    }

    @Test
    void circuitRecoversWithinTheBoundedWaitAndDispatchResumes() {
        // permits=1, stagger=0 ⇒ strict ordinal dispatch. A generous 200ms bound / 10ms poll —
        // the mocked breaker "closes" after 2 polls, so this resolves in ~20ms, well inside it.
        service = serviceWith(1, 0, 200, 10, new java.util.concurrent.CopyOnWriteArrayList<>());
        when(actions.execute(eq(ENGINE), eq("pi-1"), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));
        // pi-2's FIRST attempt trips the breaker; the bounded-wait retry (SECOND attempt, after
        // the mocked breaker reports recovered) succeeds — CallNotPermittedException guarantees
        // the first attempt never actually dispatched, so retrying it is safe (never a double-send).
        when(actions.execute(eq(ENGINE), eq("pi-2"), any(), any(), any()))
                .thenThrow(new GuardRefusedException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "engine-shedding-load",
                        "Engine 'engine-a' is shedding load (circuit open) — the action was not sent."))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));
        when(actions.execute(eq(ENGINE), eq("pi-3"), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));
        when(guardedCaller.isOpen(eq(ENGINE), eq(CallPriority.INTERACTIVE))).thenReturn(true, true, false);

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1", "pi-2", "pi-3"), responder);
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());

        // Full run: dispatch RESUMED after the bounded wait — never INTERRUPTED, no not_run.
        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.items()).extracting(BulkDtos.BulkItemDto::state).containsExactly("ok", "ok", "ok");
        // pi-2's truthful outcome is its REAL (recovered) dispatch, never the transient circuit trip.
        assertThat(done.items().get(1).detail()).doesNotContain("shedding load");
        verify(actions, org.mockito.Mockito.times(2)).execute(eq(ENGINE), eq("pi-2"), any(), any(), any());
    }

    @Test
    void circuitStillOpenAfterTheBoundedWaitGivesUpHonestly() {
        // A genuinely non-zero bound (unlike the degenerate bound=0 shortcut the OTHER give-up
        // test uses) — the breaker NEVER reports recovered, so this exercises the real poll loop
        // timing out, not just skipping it. Same final outcome as the bound=0 case: honest give-up.
        service = serviceWith(1, 0, 60, 10, new java.util.concurrent.CopyOnWriteArrayList<>());
        when(actions.execute(eq(ENGINE), eq("pi-1"), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));
        when(actions.execute(eq(ENGINE), eq("pi-2"), any(), any(), any()))
                .thenThrow(new GuardRefusedException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "engine-shedding-load",
                        "Engine 'engine-a' is shedding load (circuit open) — the action was not sent."));
        // pi-3 deliberately UNSTUBBED — must never dispatch once the bound is exceeded.
        when(guardedCaller.isOpen(eq(ENGINE), eq(CallPriority.INTERACTIVE))).thenReturn(true);

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1", "pi-2", "pi-3"), responder);
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());

        assertThat(done.state()).isEqualTo("INTERRUPTED");
        assertThat(done.items()).extracting(BulkDtos.BulkItemDto::state).containsExactly("ok", "failed", "not_run");
        assertThat(done.items().get(1).detail()).contains("shedding load");
        verify(actions, org.mockito.Mockito.times(1)).execute(eq(ENGINE), eq("pi-2"), any(), any(), any());
        verify(actions, never()).execute(eq(ENGINE), eq("pi-3"), any(), any(), any());
        // The poll loop actually ran (not the bound=0 shortcut) — at least a couple of real polls
        // within the 60ms/10ms window before giving up.
        verify(guardedCaller, org.mockito.Mockito.atLeast(2)).isOpen(eq(ENGINE), eq(CallPriority.INTERACTIVE));
    }

    /* ---------------- retry: dispatch-time DLQ resolution ---------------- */

    @Test
    void retryResolvesCurrentDeadLetterJobsPerInstance() {
        when(client.listJobs(
                        any(), eq(CallPriority.INTERACTIVE), eq(JobLaneKind.DEADLETTER), anyMap(), anyInt(), anyInt()))
                .thenReturn(page(List.of(Map.of("id", "dlq-1"), Map.of("id", "dlq-2"))));
        when(actions.execute(any(), any(), eq(ActionVerb.RETRY_JOB), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "moved"));

        BulkDtos.BulkJobDto submitted = service.submit(
                new BulkDtos.BulkSubmitRequest(
                        "retry-job",
                        "ops-4711 incident",
                        null,
                        null,
                        List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
                responder);
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());

        assertThat(done.items().get(0).state()).isEqualTo("ok");
        assertThat(done.items().get(0).detail()).contains("2 dead-letter jobs");
        verify(actions).execute(eq(ENGINE), eq("pi-1"), eq(ActionVerb.RETRY_JOB), argJob("dlq-1"), eq(responder));
        verify(actions).execute(eq(ENGINE), eq("pi-1"), eq(ActionVerb.RETRY_JOB), argJob("dlq-2"), eq(responder));
        // OPERATIONS.md §2 (issue #96): the finished job's per-item outcomes are tallied once.
        assertThat(metrics.counter("bulk_item_outcomes_total", "state", "ok").count())
                .isEqualTo(1.0);
    }

    @Test
    void retryWithNoDeadLettersLeftSkipsAsAlreadyResolved() {
        when(client.listJobs(
                        any(), eq(CallPriority.INTERACTIVE), eq(JobLaneKind.DEADLETTER), anyMap(), anyInt(), anyInt()))
                .thenReturn(page(List.of()));

        BulkDtos.BulkJobDto submitted = service.submit(
                new BulkDtos.BulkSubmitRequest(
                        "retry-job",
                        "ops-4711 incident",
                        null,
                        null,
                        List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
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
        when(client.getJob(any(), eq(CallPriority.INTERACTIVE), eq(JobLaneKind.DEADLETTER), eq("dlq-1")))
                .thenReturn(null);

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
        when(client.getJob(any(), eq(CallPriority.INTERACTIVE), eq(JobLaneKind.DEADLETTER), eq("dlq-1")))
                .thenReturn(Map.of("id", "dlq-1"));

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

    /* ---------------- v1.x #2: engine protection (permits + stagger) ---------------- */

    /** A same-mocks service with explicit bulk knobs and a recording event publisher. */
    private BulkJobService serviceWith(int permits, int staggerMs, List<Object> publishedEvents) {
        // No bounded circuit-pause wait by default — preserves the pre-#101 immediate-give-up
        // semantics for every test that doesn't care about the recovery path.
        return serviceWith(permits, staggerMs, 0, 0, publishedEvents);
    }

    /** As above, with explicit circuit-pause bound/poll knobs (issue #101, R-SEM-11). */
    private BulkJobService serviceWith(
            int permits, int staggerMs, long circuitPauseMaxMs, long circuitPausePollMs, List<Object> publishedEvents) {
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(TestEngines.engine(
                        ENGINE, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));
        return new BulkJobService(
                jobs,
                items,
                actions,
                protectedInstances,
                audit,
                registry,
                client,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new InspectorProperties(
                        null,
                        null,
                        null,
                        new InspectorProperties.Bulk(
                                permits, staggerMs, (int) circuitPauseMaxMs, (int) circuitPausePollMs),
                        List.of()),
                publishedEvents::add,
                reauthGate(),
                guardedCaller,
                // A fresh registry per instance — this helper builds a SECOND BulkJobService
                // alongside the @BeforeEach one, and both would otherwise fight over registering
                // the same bulk_jobs_running gauge id on the shared `metrics` field.
                new SimpleMeterRegistry());
    }

    @Test
    void inFlightDispatchesPerEngineNeverExceedThePermitPool() throws Exception {
        service = serviceWith(2, 0, new java.util.concurrent.CopyOnWriteArrayList<>());
        var entered = new java.util.concurrent.atomic.AtomicInteger();
        var concurrent = new java.util.concurrent.atomic.AtomicInteger();
        var maxConcurrent = new java.util.concurrent.atomic.AtomicInteger();
        var gate = new java.util.concurrent.CountDownLatch(1);
        when(actions.execute(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            entered.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                gate.await(5, TimeUnit.SECONDS);
            } finally {
                concurrent.decrementAndGet();
            }
            return new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended");
        });

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1", "pi-2", "pi-3", "pi-4"), responder);

        // Two dispatches enter and HOLD; the permit pool must pin the third out.
        await().atMost(5, TimeUnit.SECONDS).until(() -> entered.get() == 2);
        assertThat(maxConcurrent.get()).isEqualTo(2);
        gate.countDown();
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());
        assertThat(done.tallies()).containsEntry("ok", 4L);
        assertThat(maxConcurrent.get()).isEqualTo(2); // never exceeded while draining either
    }

    @Test
    void dispatchStartsOnOneEngineArePacedByTheStagger() {
        service = serviceWith(4, 120, new java.util.concurrent.CopyOnWriteArrayList<>());
        var startNanos = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        when(actions.execute(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            startNanos.add(System.nanoTime());
            return new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended");
        });

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1", "pi-2", "pi-3"), responder);
        awaitFinished(submitted.id());

        // Three dispatch starts, two mandatory 120ms pauses between them: the window from
        // first to last must span at least ~2×stagger (scheduling jitter slack included).
        List<Long> sorted = startNanos.stream().sorted().toList();
        assertThat(sorted).hasSize(3);
        long spanMs = (sorted.get(2) - sorted.get(0)) / 1_000_000;
        assertThat(spanMs).isGreaterThanOrEqualTo(200);
    }

    @Test
    void filterDoorAcceptsBeyondTheGridCapUpToTheQueryBulkCap() {
        service = serviceWith(8, 0, new java.util.concurrent.CopyOnWriteArrayList<>());
        when(actions.execute(any(), any(), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));
        String[] ids = new String[BulkJob.ITEM_CAP + 1];
        for (int i = 0; i < ids.length; i++) ids[i] = "pi-" + i;

        // The public door still refuses over 200…
        assertThatThrownBy(() -> service.submit(suspendOf(ids), responder)).isInstanceOf(GuardRefusedException.class);
        // …the filter package-door carries the same list under the 5000 cap.
        BulkDtos.BulkJobDto submitted =
                service.submit(suspendOf(ids), responder, Map.of(), BulkJob.FILTER_ITEM_CAP, "FAILED · payment");
        BulkDtos.BulkJobDto done = awaitFinished(submitted.id());
        assertThat(done.tallies()).containsEntry("ok", (long) ids.length);
    }

    /* ---------------- E1-back: scope provenance threaded from the three doors ---------------- */

    @Test
    void selectionDoorRecordsItsOwnScopeKindAndTickedCountLabel() {
        when(actions.execute(any(), any(), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1", "pi-2", "pi-3"), responder);

        assertThat(submitted.scopeKind()).isEqualTo("SELECTION");
        assertThat(submitted.scopeLabel()).isEqualTo("3 ticked instances");
    }

    @Test
    void selectionDoorSingularizesTheLabelForOneInstance() {
        when(actions.execute(any(), any(), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1"), responder);

        assertThat(submitted.scopeLabel()).isEqualTo("1 ticked instance");
    }

    @Test
    void errorClassDoorRecordsItsScopeKindAndCallerSuppliedLabel() {
        when(actions.execute(any(), any(), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));

        BulkDtos.BulkJobDto submitted = service.submit(
                new BulkDtos.BulkSubmitRequest(
                        "suspend",
                        "ops-4711 incident",
                        null,
                        null,
                        List.of(new BulkDtos.BulkTarget(ENGINE, "pi-1", null))),
                responder,
                Map.of("errorClass", Map.of()),
                "payment v3 · error class");

        assertThat(submitted.scopeKind()).isEqualTo("ERROR_CLASS");
        assertThat(submitted.scopeLabel()).isEqualTo("payment v3 · error class");
    }

    @Test
    void filterDoorRecordsItsScopeKindAndCallerSuppliedLabel() {
        service = serviceWith(8, 0, new java.util.concurrent.CopyOnWriteArrayList<>());
        when(actions.execute(any(), any(), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));

        BulkDtos.BulkJobDto submitted = service.submit(
                suspendOf("pi-1"), responder, Map.of("filter", Map.of()), BulkJob.FILTER_ITEM_CAP, "FAILED · payment");

        assertThat(submitted.scopeKind()).isEqualTo("FILTER");
        assertThat(submitted.scopeLabel()).isEqualTo("FAILED · payment");
    }

    @Test
    void publishesIdOnlyChangeEventsAcrossTheJobLifecycle() {
        List<Object> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        service = serviceWith(4, 0, published);
        when(actions.execute(any(), any(), any(), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "suspended"));

        BulkDtos.BulkJobDto submitted = service.submit(suspendOf("pi-1", "pi-2"), responder);
        awaitFinished(submitted.id());

        // submit + running + one per settled item + terminal — id-only, all for this job.
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> published.stream()
                                .filter(e -> e instanceof BulkJobChangedEvent)
                                .count()
                        >= 5);
        assertThat(published)
                .allSatisfy(e -> assertThat(e)
                        .isInstanceOfSatisfying(
                                BulkJobChangedEvent.class,
                                changed -> assertThat(changed.jobId()).isEqualTo(submitted.id())));
    }

    @Test
    void bulkJobsRunningGaugeReflectsTheRepositoryCount() {
        // OPERATIONS.md §2 (issue #96): a live gauge, not a counter — RUNNING is a point-in-time
        // state; the value comes straight from the repository query, never tracked separately.
        when(jobs.countByState(BulkJob.State.RUNNING)).thenReturn(3L);

        assertThat(metrics.get("bulk_jobs_running").gauge().value()).isEqualTo(3.0);
    }
}
