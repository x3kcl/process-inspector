package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.dto.ErrorGroup;
import io.inspector.dto.ErrorGroupAcknowledgement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 — the R-BAU-01 auto-resurface predicate, pure over crafted ack rows + a crafted
 * group. The contract (REQUIREMENTS-REGISTER R-BAU-01, SPEC §4 Stage 0 "Acknowledge"):
 * an acknowledged group collapses until (a) its member count grows past the acknowledged
 * baseline by the threshold (default +20%), (b) a NEW definition version appears among
 * the failing members, or (c) the acknowledgment's optional expiry passes — then it
 * resurfaces (rendered with the "GREW SINCE ACK: +n" badge family), never staying muted.
 */
class ErrorGroupAckPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");
    private static final Instant ACKED_AT = Instant.parse("2026-07-10T09:00:00Z");
    private static final int THRESHOLD_PCT = 20;

    private static ErrorGroup group(long total, Map<String, Map<String, Long>> countsByEngine) {
        return new ErrorGroup(
                "hash-1", 1, "java.lang.NullPointerException", "boom #", "boom 42", total, total, 0, countsByEngine);
    }

    private static ErrorGroupAck ack(String engineId, String defKey, long count, Integer maxVersion, Instant expires) {
        return new ErrorGroupAck(
                "hash-1",
                1,
                engineId,
                defKey,
                "operator",
                "known tax-service outage",
                "OPS-7",
                ACKED_AT,
                expires,
                count,
                maxVersion);
    }

    @Test
    void noRowsMeansNoAcknowledgement() {
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 10L)));
        assertThat(ErrorGroupAckPolicy.evaluate(g, List.of(), THRESHOLD_PCT, NOW))
                .isNull();
    }

    @Test
    void steadyStateStaysAcknowledged() {
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 10L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isFalse();
        assertThat(info.resurfaceReason()).isNull();
        assertThat(info.grownBy()).isZero();
        assertThat(info.acknowledgedBy()).isEqualTo("operator");
        assertThat(info.reason()).isEqualTo("known tax-service outage");
        assertThat(info.ticketId()).isEqualTo("OPS-7");
        assertThat(info.acknowledgedTotal()).isEqualTo(10);
        assertThat(info.acknowledgedAt()).isEqualTo(ACKED_AT.toString());
    }

    @Test
    void growthWithinThresholdStaysAcknowledged() {
        // +20% of 10 = 12: exactly at the threshold is NOT past it.
        ErrorGroup g = group(12, Map.of("e1", Map.of("orders:v3", 12L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isFalse();
        assertThat(info.grownBy()).isEqualTo(2);
    }

    @Test
    void growthPastThresholdResurfaces() {
        ErrorGroup g = group(13, Map.of("e1", Map.of("orders:v3", 13L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isTrue();
        assertThat(info.resurfaceReason()).isEqualTo("grew");
        assertThat(info.grownBy()).isEqualTo(3);
    }

    @Test
    void thresholdIsConfigurable() {
        ErrorGroup g = group(13, Map.of("e1", Map.of("orders:v3", 13L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), 50, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isFalse();
    }

    @Test
    void newDefinitionVersionResurfaces() {
        // v4 appeared with failures after the ack recorded max v3.
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 6L, "orders:v4", 4L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isTrue();
        assertThat(info.resurfaceReason()).isEqualTo("new-version");
    }

    @Test
    void zeroFilledNewVersionDoesNotResurface() {
        // The aggregation zero-fills sibling versions ("v4: 0") — a DEPLOYED new version
        // with no failures is not a resurface signal; only failing members count.
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 10L, "orders:v4", 0L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isFalse();
    }

    @Test
    void newEngineOrDefinitionSliceResurfaces() {
        // The class started failing on an engine/definition the ack never covered.
        ErrorGroup g = group(11, Map.of("e1", Map.of("orders:v3", 10L), "e2", Map.of("billing:v1", 1L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isTrue();
        assertThat(info.resurfaceReason()).isEqualTo("new-version");
    }

    @Test
    void expiryPassingResurfaces() {
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 10L)));
        ErrorGroupAcknowledgement info = ErrorGroupAckPolicy.evaluate(
                g, List.of(ack("e1", "orders", 10, 3, NOW.minusSeconds(60))), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isTrue();
        assertThat(info.resurfaceReason()).isEqualTo("expired");
        assertThat(info.expiresAt()).isEqualTo(NOW.minusSeconds(60).toString());
    }

    @Test
    void futureExpiryStaysAcknowledged() {
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 10L)));
        ErrorGroupAcknowledgement info = ErrorGroupAckPolicy.evaluate(
                g, List.of(ack("e1", "orders", 10, 3, NOW.plusSeconds(3600))), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isFalse();
    }

    @Test
    void newVersionOutranksGrowthAsTheReason() {
        // Both trip: the more specific signal wins the label; grownBy still reports the delta.
        ErrorGroup g = group(20, Map.of("e1", Map.of("orders:v3", 10L, "orders:v4", 10L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaceReason()).isEqualTo("new-version");
        assertThat(info.grownBy()).isEqualTo(10);
    }

    @Test
    void shrinkingNeverReportsNegativeGrowth() {
        ErrorGroup g = group(4, Map.of("e1", Map.of("orders:v3", 4L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isFalse();
        assertThat(info.grownBy()).isZero();
    }

    @Test
    void displayFieldsComeFromTheLatestRow() {
        ErrorGroupAck older = new ErrorGroupAck(
                "hash-1",
                1,
                "e1",
                "orders",
                "nightshift",
                "first look at this",
                null,
                ACKED_AT.minusSeconds(7200),
                null,
                5,
                3);
        ErrorGroupAck newer = ack("e2", "billing", 5, 1, null);
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 5L), "e2", Map.of("billing:v1", 5L)));
        ErrorGroupAcknowledgement info = ErrorGroupAckPolicy.evaluate(g, List.of(older, newer), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.acknowledgedBy()).isEqualTo("operator");
        assertThat(info.acknowledgedAt()).isEqualTo(ACKED_AT.toString());
        assertThat(info.reason()).isEqualTo("known tax-service outage"); // newest row's, not "first look"
        assertThat(info.ticketId()).isEqualTo("OPS-7");
        assertThat(info.acknowledgedTotal()).isEqualTo(10);
    }

    @Test
    void mixedTriggersAcrossSlicesStillResurfaceAsNewVersion() {
        // Two slices trip DIFFERENT mechanisms in one evaluation: e1/orders bumped its
        // failing version (v3 → v4) AND e2/billing is a slice the ack never covered —
        // the loop must not skip either (external review).
        ErrorGroup g = group(12, Map.of("e1", Map.of("orders:v4", 10L), "e2", Map.of("billing:v1", 2L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "orders", 10, 3, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isTrue();
        assertThat(info.resurfaceReason()).isEqualTo("new-version");
        assertThat(info.grownBy()).isEqualTo(2);
    }

    @Test
    void unparsableVersionsNeverTripTheVersionCheck() {
        ErrorGroup g = group(10, Map.of("e1", Map.of("unknown:v?", 10L)));
        ErrorGroupAcknowledgement info =
                ErrorGroupAckPolicy.evaluate(g, List.of(ack("e1", "unknown", 10, null, null)), THRESHOLD_PCT, NOW);
        assertThat(info).isNotNull();
        assertThat(info.resurfaced()).isFalse();
    }
}
