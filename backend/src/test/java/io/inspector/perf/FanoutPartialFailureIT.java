package io.inspector.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.aggregate.SearchService;
import io.inspector.client.GuardedCaller;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.support.NoDbTestSupport;
import io.inspector.support.StubFlowableEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * R-TEST-05 P1 (issue #298, TEST-STRATEGY.md §7): "10 stub engines, 3 slow (read-ms−500), 2
 * timing out" — the partial-failure fan-out do-no-harm proof that was, until now, entirely
 * unautomated (TRACEABILITY-MATRIX.md §C-19). The sibling P2 suite ({@link
 * io.inspector.perf.DlqScanCapVolumeIT}, issue #299/PR #322) built {@link StubFlowableEngine}
 * as an explicitly reusable foundation for exactly this suite — extended here, not
 * re-implemented: {@link StubFlowableEngine#withResponseDelayMs} and {@link
 * StubFlowableEngine#hangForever} inject the slow/timeout fixtures, and the new {@link
 * StubFlowableEngine#recoverToHealthy} (added by this change) flips a degraded stub back for
 * the breaker half-open→closed proof.
 *
 * <p><b>Naming-collision note</b> (issue #298): TEST-STRATEGY's "P1" is an ORDINAL in its
 * performance-scenario catalog, not an incident-severity tier. The existing nightly job
 * {@code perf-p1} runs {@code scripts/perf-scenario-p1.js} — that is TEST-STRATEGY's P4 (a
 * latency SLA against 5 HEALTHY engines, R-NFR-02, §C-10), an unrelated scenario that merely
 * happens to share the "p1" substring. This suite's own nightly job is deliberately named
 * {@code fanout-partial-failure-p1} — never {@code perf-p1}, never {@code perf-scenario-p1.js}.
 *
 * <p><b>10 engines, config-pinned registry test seam</b> ({@code
 * application-it-p1fanout.yml}, {@code inspector.registry.source: config}): 5 healthy
 * (instant, empty responses), 3 slow ({@code withResponseDelayMs(read-ms − 500)} = 1000ms,
 * under the 1500ms read timeout so these calls SUCCEED, just slowly), 2 timing out ({@code
 * hangForever()} — the stub never answers at all, so the CLIENT's OWN read timeout is what
 * ends the call, exactly the do-no-harm contract under test). Stubs fake latency/timeout
 * ONLY — every route answers the same paged job-query contract {@link
 * io.inspector.aggregate.SearchService} calls in production (join/status/paging semantics stay
 * proven at rung 4 against real flowable-rest elsewhere: {@code SearchServiceIT}, {@code
 * TriageCmmnScopeIT} — the never-mock-Flowable iron rule).
 *
 * <p><b>Test independence</b>: every test that cares about circuit-breaker STATE calls {@link
 * GuardedCaller#evict} on the engine(s) it touches BEFORE asserting, so those tests never depend
 * on what a PREVIOUS test (or the background {@code EngineHealthService} health probe, which
 * also calls through the SAME per-engine breaker/bulkhead instances on its own 30s cycle —
 * TEST-STRATEGY §9/the P2 suite's own javadoc name this same interleaving) left the BREAKER in.
 * {@code evict()} cannot un-heal a STUB, though: {@link #breakerClosesOnceTheStubRecovers()} is
 * the one test that permanently flips {@code hang-2} back to healthy for the rest of the class
 * (the issue's own "flip one stub back to healthy" fixture), so {@link MethodOrderer.OrderAnnotation}
 * pins it strictly LAST — every other test's "2 timing out" assumption stays valid throughout.
 *
 * <p>Assertions map directly to the issue's plan:
 * <ul>
 *   <li>{@link #healthyEnginesStayFastAndTheEnvelopeIsHonestUnderPartialFailure()} — search P95
 *       (single representative call, nightly cadence) stays within {@code read-ms + 2s}; the 2
 *       timed-out engines are marked {@code ok=false} with a real error, NEVER a fabricated
 *       zero/healthy result (ARCHITECTURE §2.3); the healthy/slow engines' OWN recorded
 *       latency (the {@code engine_fanout_duration_seconds} chokepoint metric, not the whole
 *       call's wall time) stays an order of magnitude below the slow/timed-out ones — direct
 *       proof the degraded engines never contended for a healthy engine's own resources.</li>
 *   <li>{@link #actuatorHealthStaysResponsiveWhileTheFanoutDegrades()} — BFF do-no-harm: the
 *       unauthenticated readiness surface never touches an engine (R-OPS-01), so it must stay
 *       fast even while several engines are genuinely hung/slow in flight.</li>
 *   <li>{@link #breakerOpensOnRepeatedFailureAndFastFailsWhileOpen()} /
 *       {@link #breakerClosesOnceTheStubRecovers()} — the do-no-harm breaker transition.</li>
 *   <li>{@link #noBulkheadStarvationAcrossEngines()} — a genuinely SATURATED slow engine's
 *       bulkhead never borrows from / blocks a different engine's own permits.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it-p1fanout")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(NoDbTestSupport.class)
class FanoutPartialFailureIT {

    private static final int READ_MS = 1500;
    private static final int SLOW_DELAY_MS = READ_MS - 500;
    /** TEST-STRATEGY §7 P1 threshold: "search P95 ≤ read-ms+2s". */
    private static final Duration SEARCH_BUDGET = Duration.ofMillis(READ_MS + 2000);
    /** Generous vs. the ~1000ms slow delay / ~1500ms real timeout — a fast-fail proof bound. */
    private static final Duration FAST_FAIL_BOUND = Duration.ofMillis(400);
    /** Generous vs. the 1000ms slow delay — proves a healthy call never queued behind one. */
    private static final long HEALTHY_TIMER_BOUND_MS = 500;

    private static final List<String> HEALTHY_IDS =
            List.of("healthy-1", "healthy-2", "healthy-3", "healthy-4", "healthy-5");
    private static final List<String> SLOW_IDS = List.of("slow-1", "slow-2", "slow-3");
    private static final List<String> HANG_IDS = List.of("hang-1", "hang-2");

    private static final StubFlowableEngine HEALTHY_1 = new StubFlowableEngine(0);
    private static final StubFlowableEngine HEALTHY_2 = new StubFlowableEngine(0);
    private static final StubFlowableEngine HEALTHY_3 = new StubFlowableEngine(0);
    private static final StubFlowableEngine HEALTHY_4 = new StubFlowableEngine(0);
    private static final StubFlowableEngine HEALTHY_5 = new StubFlowableEngine(0);
    private static final StubFlowableEngine SLOW_1 = new StubFlowableEngine(0).withResponseDelayMs(SLOW_DELAY_MS);
    private static final StubFlowableEngine SLOW_2 = new StubFlowableEngine(0).withResponseDelayMs(SLOW_DELAY_MS);
    private static final StubFlowableEngine SLOW_3 = new StubFlowableEngine(0).withResponseDelayMs(SLOW_DELAY_MS);
    private static final StubFlowableEngine HANG_1 = new StubFlowableEngine(0).hangForever();
    private static final StubFlowableEngine HANG_2 = new StubFlowableEngine(0).hangForever();

    @DynamicPropertySource
    static void registerStubs(DynamicPropertyRegistry registry) {
        registry.add("P1_PORT_HEALTHY_1", HEALTHY_1::port);
        registry.add("P1_PORT_HEALTHY_2", HEALTHY_2::port);
        registry.add("P1_PORT_HEALTHY_3", HEALTHY_3::port);
        registry.add("P1_PORT_HEALTHY_4", HEALTHY_4::port);
        registry.add("P1_PORT_HEALTHY_5", HEALTHY_5::port);
        registry.add("P1_PORT_SLOW_1", SLOW_1::port);
        registry.add("P1_PORT_SLOW_2", SLOW_2::port);
        registry.add("P1_PORT_SLOW_3", SLOW_3::port);
        registry.add("P1_PORT_HANG_1", HANG_1::port);
        registry.add("P1_PORT_HANG_2", HANG_2::port);
    }

    @AfterAll
    static void stopStubs() {
        for (StubFlowableEngine stub : List.of(
                HEALTHY_1, HEALTHY_2, HEALTHY_3, HEALTHY_4, HEALTHY_5, SLOW_1, SLOW_2, SLOW_3, HANG_1, HANG_2)) {
            stub.close();
        }
    }

    @Autowired
    SearchService searchService;

    @Autowired
    GuardedCaller guardedCaller;

    @Autowired
    CircuitBreakerRegistry breakers;

    @Autowired
    BulkheadRegistry bulkheads;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    TestRestTemplate rest;

    private static SearchRequest failedOnlyRequest(List<String> engineIds) {
        return new SearchRequest(
                engineIds,
                List.of(InstanceStatus.FAILED),
                null, // processDefinitionKey
                null, // definitionVersion
                null, // businessKey
                null, // businessKeyLike
                null, // startedAfter
                null, // startedBefore
                null, // failureTimeAfter
                null, // failureTimeBefore
                null, // errorText
                null, // signatureHash
                null, // currentActivity
                Collections.emptyList(), // variables
                null, // sortBy
                null, // pageSize
                null, // cursor
                null); // signatureAlgoVersion
    }

    private void evictAll() {
        for (String id : HEALTHY_IDS) guardedCaller.evict(id);
        for (String id : SLOW_IDS) guardedCaller.evict(id);
        for (String id : HANG_IDS) guardedCaller.evict(id);
    }

    @Test
    @Order(1)
    void healthyEnginesStayFastAndTheEnvelopeIsHonestUnderPartialFailure() {
        evictAll();
        // Warm the healthy/slow engines' RestClient/HttpClient (first-call connection setup is
        // real overhead unrelated to the property under test — it would happen even if EVERY
        // engine were healthy) BEFORE the timed measurement below — never against hang-1/hang-2,
        // whose first call must stay a genuine, uncached timeout for the honesty assertions
        // further down. Then drop the warm-up's own recorded metric samples (Timer#max reflects
        // the max of every sample ever recorded, so a left-in cold sample would keep failing the
        // "stayed fast" assertion below even after warming) so the Timer instances the real,
        // timed call below creates start clean. Observed without this: a cold first call ~510ms
        // vs. a warm ~50ms — real overhead, not cross-engine contention.
        List<String> warmIds = new ArrayList<>(HEALTHY_IDS);
        warmIds.addAll(SLOW_IDS);
        searchService.search(failedOnlyRequest(warmIds));
        for (String id : warmIds) {
            Timer warm = engineTimer(id);
            if (warm != null) meterRegistry.remove(warm);
        }

        Instant started = Instant.now();
        SearchResponse response = searchService.search(failedOnlyRequest(null)); // all 10 engines
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(elapsed)
                .as("TEST-STRATEGY §7 P1: search P95 must stay within read-ms + 2s even with 2 engines "
                        + "genuinely timing out and 3 genuinely slow")
                .isLessThanOrEqualTo(SEARCH_BUDGET);

        assertThat(response.perEngine()).hasSize(10);
        for (String id : HEALTHY_IDS) {
            assertThat(response.perEngine().get(id).ok())
                    .as("healthy engine %s must be ok", id)
                    .isTrue();
        }
        for (String id : SLOW_IDS) {
            assertThat(response.perEngine().get(id).ok())
                    .as("slow engine %s must still succeed — it answers inside its read timeout", id)
                    .isTrue();
        }
        for (String id : HANG_IDS) {
            EngineResult result = response.perEngine().get(id);
            assertThat(result.ok())
                    .as("ARCHITECTURE §2.3: a timed-out engine must be marked ok=false, never a fabricated"
                            + " healthy/zero result")
                    .isFalse();
            assertThat(result.error())
                    .as("the envelope must carry an honest reason, not a silent blank", id)
                    .isNotBlank();
        }

        // Direct proof of no cross-engine starvation: each HEALTHY engine's OWN recorded call
        // latency (the GuardedCaller chokepoint metric, not the whole search() wall time) stayed
        // fast despite running in the SAME fan-out as 3 slow + 2 hung engines. If a slow/hung
        // engine's load had bled into a healthy engine's own resources, this would show it —
        // whole-call wall time alone (asserted above) cannot distinguish "everyone was fast" from
        // "the slow/hung legs happened to finish just under budget".
        for (String id : HEALTHY_IDS) {
            Timer timer = engineTimer(id);
            assertThat(timer)
                    .as("engine_fanout_duration_seconds must be recorded for %s", id)
                    .isNotNull();
            assertThat(timer.max(TimeUnit.MILLISECONDS))
                    .as("healthy engine %s's own call latency must stay far below the slow/hung engines'", id)
                    .isLessThan(HEALTHY_TIMER_BOUND_MS);
        }
        // Corroborate the fixture actually behaved as configured (never trust the envelope alone):
        // the slow/hung engines' OWN recorded latency really did approach their configured delay.
        for (String id : SLOW_IDS) {
            assertThat(engineTimer(id).max(TimeUnit.MILLISECONDS))
                    .as("slow engine %s must have genuinely taken close to its injected delay", id)
                    .isGreaterThanOrEqualTo(SLOW_DELAY_MS - 100);
        }
    }

    private Timer engineTimer(String engineId) {
        return meterRegistry
                .find("engine_fanout_duration_seconds")
                .tag("engineId", engineId)
                .tag("leg", CallPriority.INTERACTIVE.name())
                .timer();
    }

    @Test
    @Order(2)
    void actuatorHealthStaysResponsiveWhileTheFanoutDegrades() throws Exception {
        evictAll();
        ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
        try {
            List<Long> idleBaselineMs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                idleBaselineMs.add(timedHealthCall());
            }

            Future<SearchResponse> future = searchExecutor.submit(() -> searchService.search(failedOnlyRequest(null)));

            List<Long> duringSearchMs = new ArrayList<>();
            while (!future.isDone()) {
                duringSearchMs.add(timedHealthCall());
            }
            // A handful more once the busy window is confirmed over, for a fuller sample.
            for (int i = 0; i < 3; i++) {
                duringSearchMs.add(timedHealthCall());
            }
            future.get(SEARCH_BUDGET.toMillis() * 2, TimeUnit.MILLISECONDS);

            assertThat(duringSearchMs)
                    .as("the fan-out must genuinely have been in flight long enough to sample the health "
                            + "endpoint several times DURING it — otherwise this proves nothing")
                    .hasSizeGreaterThanOrEqualTo(3);

            long idleMax =
                    idleBaselineMs.stream().mapToLong(Long::longValue).max().orElse(0);
            long duringMax =
                    duringSearchMs.stream().mapToLong(Long::longValue).max().orElse(0);
            // Do-no-harm (ARCHITECTURE §2.2/§2.3, R-OPS-01): the readiness surface never calls an
            // engine, so its latency should be essentially unaffected by 2 engines hanging + 3
            // running slow. Bound is a hybrid — relative to this run's own idle baseline (defends
            // against a genuinely loaded shared CI runner slot skewing an absolute figure) PLUS a
            // generous absolute ceiling (documented looser than TEST-STRATEGY's illustrative
            // "<200ms" precisely because this runs nightly on a shared, potentially busy
            // dockerized runner slot — the property under test is "stays responsive", not "hits
            // an exact millisecond figure on shared hardware").
            long hybridBoundMs = Math.max(idleMax * 10, 1500);
            assertThat(duringMax)
                    .as("actuator/health latency during fan-out degradation (idle baseline max was %dms)", idleMax)
                    .isLessThanOrEqualTo(hybridBoundMs);
        } finally {
            searchExecutor.shutdownNow();
        }
    }

    private long timedHealthCall() {
        Instant start = Instant.now();
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return elapsedMs;
    }

    @Test
    @Order(3)
    void breakerOpensOnRepeatedFailureAndFastFailsWhileOpen() {
        guardedCaller.evict("hang-1");
        SearchRequest scoped = failedOnlyRequest(List.of("hang-1"));

        // Two consecutive real failures (the test profile's breaker: window 2 / min-calls 2) —
        // both calls must be genuinely slow (near the real read timeout), proving the breaker
        // started CLOSED and these are REAL timeouts, not already-open fast-fails.
        for (int i = 0; i < 2; i++) {
            Instant started = Instant.now();
            SearchResponse response = searchService.search(scoped);
            Duration elapsed = Duration.between(started, Instant.now());
            assertThat(response.perEngine().get("hang-1").ok()).isFalse();
            assertThat(elapsed)
                    .as("call %d must be a REAL read-timeout, not an already-open fast-fail", i + 1)
                    .isGreaterThanOrEqualTo(Duration.ofMillis(READ_MS - 100));
        }

        assertThat(guardedCaller.isOpen("hang-1", CallPriority.INTERACTIVE))
                .as("2 consecutive failures within the 2-call sliding window must trip the breaker OPEN")
                .isTrue();

        // Do-no-harm payoff: the NEXT call must fast-fail (CallNotPermittedException), never wait
        // out another full read timeout.
        Instant started = Instant.now();
        SearchResponse response = searchService.search(scoped);
        Duration elapsed = Duration.between(started, Instant.now());
        assertThat(response.perEngine().get("hang-1").ok()).isFalse();
        assertThat(elapsed)
                .as("an OPEN breaker must fast-fail — the whole point of do-no-harm")
                .isLessThan(FAST_FAIL_BOUND);
    }

    @Test
    @Order(5)
    void breakerClosesOnceTheStubRecovers() {
        guardedCaller.evict("hang-2");
        SearchRequest scoped = failedOnlyRequest(List.of("hang-2"));

        for (int i = 0; i < 2; i++) {
            searchService.search(scoped);
        }
        assertThat(guardedCaller.isOpen("hang-2", CallPriority.INTERACTIVE))
                .as("precondition: the breaker must be OPEN before recovery is meaningful")
                .isTrue();

        // The reusable seam (#298, added to StubFlowableEngine): flip the stub back to instant,
        // healthy responses.
        HANG_2.recoverToHealthy();

        // Read-only polling (never a mutation) — each iteration's own search() call IS the trial
        // call Resilience4j needs to observe the recovery; explicit bound, no Thread.sleep.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    SearchResponse response = searchService.search(scoped);
                    assertThat(breakers.circuitBreaker("hang-2", "engine").getState())
                            .as("the breaker must fully CLOSE once the stub answers healthily again")
                            .isEqualTo(CircuitBreaker.State.CLOSED);
                    assertThat(response.perEngine().get("hang-2").ok())
                            .as("the recovered engine's own search must now succeed")
                            .isTrue();
                });
    }

    @Test
    @Order(4)
    void noBulkheadStarvationAcrossEngines() throws Exception {
        guardedCaller.evict("slow-1");
        guardedCaller.evict("healthy-1");
        SearchRequest slowOnly = failedOnlyRequest(List.of("slow-1"));
        SearchRequest healthyOnly = failedOnlyRequest(List.of("healthy-1"));

        // The test profile's bulkhead cap is 2 concurrent calls per engine — 3 concurrent calls
        // to the SAME slow engine genuinely saturates it (one must queue behind the other two).
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<CompletableFuture<SearchResponse>> slowCalls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                slowCalls.add(CompletableFuture.supplyAsync(() -> searchService.search(slowOnly), pool));
            }

            Bulkhead slowBulkhead = bulkheads.bulkhead("slow-1", "engine");
            await().atMost(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() -> assertThat(slowBulkhead.getMetrics().getAvailableConcurrentCalls())
                            .as("3 concurrent calls against a 2-permit bulkhead must genuinely saturate it")
                            .isLessThan(slowBulkhead.getMetrics().getMaxAllowedConcurrentCalls()));

            // WHILE slow-1 is saturated, a DIFFERENT engine's own (separately-keyed) bulkhead must
            // be entirely unaffected — the direct proof of no cross-engine starvation.
            Instant started = Instant.now();
            SearchResponse healthyResponse = searchService.search(healthyOnly);
            Duration healthyElapsed = Duration.between(started, Instant.now());
            assertThat(healthyResponse.perEngine().get("healthy-1").ok()).isTrue();
            assertThat(healthyElapsed)
                    .as("a different engine's own bulkhead permit must never queue behind slow-1's saturation")
                    .isLessThan(Duration.ofMillis(HEALTHY_TIMER_BOUND_MS));

            // All 3 queued slow-1 calls must still eventually succeed (queued, never rejected,
            // within the bulkhead's max-wait-duration) — corroborates genuine contention+recovery,
            // not a silently swallowed failure.
            for (CompletableFuture<SearchResponse> call : slowCalls) {
                SearchResponse response = call.get(10, TimeUnit.SECONDS);
                assertThat(response.perEngine().get("slow-1").ok()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
