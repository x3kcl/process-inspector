package io.inspector.perf;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.aggregate.SearchService;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.snapshot.AggregationSample;
import io.inspector.snapshot.PollingSnapshotSource;
import io.inspector.support.NoDbTestSupport;
import io.inspector.support.StubFlowableEngine;
import io.inspector.triage.TriageAggregationService;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * R-TEST-05 P2 (issue #299, TEST-STRATEGY.md §7): "50k-row DLQ vs {@code dlq-scan-cap}", at the
 * DOCUMENTED operating scale — {@code dlq-scan-cap: 5000} / {@code max-page-size: 200}, the real
 * {@code InspectorProperties.EngineConfig} defaults — rather than the artificially tiny cap
 * ({@code engine-tiny}, cap 2) the existing {@code SearchServiceIT}/{@code TriageCmmnScopeIT}
 * truncation fixtures use (three orders of magnitude below what this suite proves). A single
 * in-process {@link StubFlowableEngine} (docker-free — no compose stack, no seeding, ~50k rows
 * generated ON THE FLY, never materialized) answers the SAME paged job-query contract
 * {@link TriageAggregationService} (the Stage-0 dashboard) and {@link SearchService} (the
 * FAILED-only inverted plan) actually call in production. This is a data-VOLUME fixture, not a
 * join-logic mock — the never-mock-Flowable iron rule governs status/paging semantics, proven
 * at rung 4 against real flowable-rest elsewhere.
 *
 * <p>Proves (issue #299 plan items 2/3):
 * <ul>
 *   <li>the scan STOPS at {@code dlq-scan-cap} — the cap boundary (start 4800, the last page
 *       before 5000) is reached and NEVER exceeded, and no single request ever asked for more
 *       than {@code max-page-size} rows (never one unpaged fetch);</li>
 *   <li>it completes within the documented ≤10s bound;</li>
 *   <li>every derived count is reported as a lower bound with the {@code truncated@N} marker,
 *       on BOTH the Stage-0 dashboard and the FAILED-only search plan;</li>
 *   <li>the Stage-0 status counts stay count-only/{@code size=1} (structurally unaffected by
 *       this fixture — ACTIVE/SUSPENDED/COMPLETED hit different, non-paged endpoints entirely);</li>
 *   <li>the sampler that feeds the incident ledger derives {@code truncatedEngineIds} correctly
 *       from a REAL capped cycle at volume — the exact input {@code IncidentLedgerService} needs
 *       to mark an occurrence {@code truncated=true} (that downstream derivation is rung-1
 *       proven by {@code IncidentLedgerServiceTest#truncationFlagsPropagateIntoIncidentAndOccurrence};
 *       this suite proves the SOURCE flag is set correctly at realistic volume, not the
 *       derivation again).</li>
 * </ul>
 *
 * <p>Request-count assertions deliberately avoid an EXACT tally: {@code EngineHealthService}'s
 * own {@code @Scheduled} probe (health-strip lane counts, {@code size=1}) may fire concurrently
 * against the same stub and would make an exact count a time bomb. Asserting the actual
 * {@code start}/{@code size} offsets seen is immune to that interleaving.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "DLQ_STUB_PASSWORD=test")
@ActiveProfiles("it-dlqvolume")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NoDbTestSupport.class)
class DlqScanCapVolumeIT {

    private static final long DLQ_TOTAL_ROWS = 50_000;
    private static final int DLQ_SCAN_CAP = 5_000;
    private static final int MAX_PAGE_SIZE = 200;
    private static final Duration SCAN_BUDGET = Duration.ofSeconds(10);

    private static final StubFlowableEngine stub = new StubFlowableEngine(DLQ_TOTAL_ROWS);

    @DynamicPropertySource
    static void registerStub(DynamicPropertyRegistry registry) {
        registry.add("DLQ_STUB_PORT", stub::port);
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @Autowired
    TriageAggregationService triageAggregation;

    @Autowired
    SearchService searchService;

    @Autowired
    PollingSnapshotSource snapshotSource;

    @Test
    void stage0AggregationStopsAtTheCapAndBadgesEveryCountALowerBound() {
        Instant started = Instant.now();
        TriageDashboardResponse dashboard = triageAggregation.aggregate();
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(elapsed)
                .as("a capped scan against 50k synthetic rows must complete within the documented budget")
                .isLessThanOrEqualTo(SCAN_BUDGET);

        var engineEnvelope = dashboard.perEngine().get("dlq-volume");
        assertThat(engineEnvelope.ok()).isTrue();
        assertThat(engineEnvelope.dlqScan())
                .as("the DEADLETTER lane scan hit dlq-scan-cap (5000) and must badge, not lie")
                .isEqualTo("truncated@" + DLQ_SCAN_CAP);

        // FAILED is synthesized from the capped scan — every one of the 5000 scanned rows is a
        // distinct instance, so the derived count is exactly the cap: a LOWER bound of the real
        // 50k. Stage-0's ACTIVE/SUSPENDED/COMPLETED stay count-only/size=1 structurally (they hit
        // /query/process-instances and /query/historic-process-instances with no
        // processInstanceIds, never the paged job lane) — this fixture cannot perturb them.
        assertThat(dashboard.statusCountsByEngine().get("dlq-volume").get("FAILED"))
                .isEqualTo((long) DLQ_SCAN_CAP);

        assertCapBoundaryRespected();
    }

    @Test
    void failedOnlySearchScanCapsAndBadgesTruncated() {
        SearchRequest request = new SearchRequest(
                null, // all enabled engines
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

        Instant started = Instant.now();
        SearchResponse response = searchService.search(request);
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(elapsed)
                .as("a capped FAILED-only scan against 50k synthetic rows must complete within the documented budget")
                .isLessThanOrEqualTo(SCAN_BUDGET);

        var engineResult = response.perEngine().get("dlq-volume");
        assertThat(engineResult.ok()).isTrue();
        assertThat(engineResult.dlqScan())
                .as("the inverted DLQ-driven plan must badge the same cap, never silently declassify")
                .isEqualTo("truncated@" + DLQ_SCAN_CAP);
        assertThat(engineResult.total())
                .as("the reported total is a LOWER bound (the real DLQ has 50k, only 5000 were scanned)")
                .isEqualTo((long) DLQ_SCAN_CAP);

        assertCapBoundaryRespected();
    }

    @Test
    void snapshotSampleFlagsTheCappedEngineTruncated() {
        AggregationSample sample = snapshotSource.sample();

        assertThat(sample.truncatedEngineIds())
                .as("R-SEM-12: PollingSnapshotSource must derive the truncation flag from a REAL capped "
                        + "cycle at volume — the exact input IncidentLedgerService needs to mark an "
                        + "occurrence truncated=true")
                .containsExactly("dlq-volume");
        assertThat(sample.cycleComplete())
                .as("a capped scan is still an OBSERVED cycle, not a blind one — cycleComplete tracks "
                        + "engine reachability, not scan completeness (R-BAU-10, issue #302)")
                .isTrue();

        assertCapBoundaryRespected();
    }

    /**
     * The "never an unpaged fetch" proof, immune to concurrent health-probe noise (see class
     * javadoc): the scan reached the last page before the cap (start 4800) and never asked past
     * it, and no single request's {@code size} exceeded {@code max-page-size} — i.e. nothing
     * ever asked the stub for "everything at once".
     */
    private void assertCapBoundaryRespected() {
        assertThat(stub.deadletterStartsSeen())
                .as("the scan must reach the last page before the cap")
                .contains(DLQ_SCAN_CAP - MAX_PAGE_SIZE);
        assertThat(stub.deadletterStartsSeen())
                .as("the scan must never page past dlq-scan-cap")
                .allMatch(start -> start < DLQ_SCAN_CAP);
        assertThat(stub.maxDeadletterSizeRequested())
                .as("never a single unpaged fetch — every page request stays within max-page-size")
                .isLessThanOrEqualTo(MAX_PAGE_SIZE);
    }
}
