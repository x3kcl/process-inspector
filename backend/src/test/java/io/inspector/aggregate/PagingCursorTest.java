package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.aggregate.PagingCursor.PageResult;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the deep-paging cursor's codec, inbound bound-check, filter binding,
 * and the pure bounded k-way {@link PagingCursor#mergePage} — the R-SEM-22 / R-NFR-08 core, proved
 * without any engine I/O (the live deep-scroll correctness is rung-4, S5).
 */
class PagingCursorTest {

    private static ProcessInstanceRow row(String compositeId, String startTime) {
        String engineId = compositeId.substring(0, compositeId.indexOf(':')); // "a:1" -> "a"
        return new ProcessInstanceRow(
                compositeId,
                engineId,
                null,
                null,
                "pid",
                null,
                null,
                null,
                null,
                null,
                null,
                "bpmn",
                null,
                null,
                startTime,
                null,
                null,
                null,
                null,
                null);
    }

    private static PagingCursor cursor(
            long issuedAt,
            String filterHash,
            Map<String, Integer> offsets,
            String boundaryKey,
            List<String> boundaryIds) {
        return new PagingCursor(
                PagingCursor.VERSION, issuedAt, filterHash, "startTime", "desc", offsets, boundaryKey, boundaryIds);
    }

    /* ---------------- codec ---------------- */

    @Nested
    class Codec {
        @Test
        void roundTripsThroughBase64UrlJson() {
            PagingCursor c = cursor(
                    123L, "abc", Map.of("engine-a", 200, "engine-b", 0), "2026-07-09T10:00:00Z", List.of("engine-a:1"));
            PagingCursor back = PagingCursor.decode(c.encode());
            assertThat(back).isEqualTo(c);
        }

        @Test
        void encodeIsUrlSafeAndUnpadded() {
            String token =
                    cursor(1L, "h", Map.of("engine-a", 1), null, List.of()).encode();
            assertThat(token).doesNotContain("+", "/", "=");
        }

        @Test
        void garbageDecodesToA400IllegalArgument() {
            assertThatThrownBy(() -> PagingCursor.decode("!!!not-base64!!!"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PagingCursor.decode("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PagingCursor.decode(null)).isInstanceOf(IllegalArgumentException.class);
            // valid base64url of non-cursor JSON
            assertThatThrownBy(() -> PagingCursor.decode(java.util.Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString("{\"unrelated\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /* ---------------- inbound bound-check (the DoS ceiling, R-NFR-08) ---------------- */

    @Nested
    class ValidateInbound {
        final Map<String, Integer> caps = Map.of("engine-a", 5000, "engine-b", 5000);
        final long now = 1_000_000L;

        PagingCursor good(Map<String, Integer> offsets) {
            return cursor(now, "HASH", offsets, "2026-07-09T10:00:00Z", List.of());
        }

        @Test
        void acceptsAWellFormedInBoundsCursor() {
            good(Map.of("engine-a", 200, "engine-b", 0)).validateInbound("HASH", "startTime", caps, 600_000L, now);
        }

        @Test
        void rejectsAnOffsetOverThePerEngineCapBeforeFanOut() {
            assertThatThrownBy(() -> good(Map.of("engine-a", 9_999_999))
                            .validateInbound("HASH", "startTime", caps, 600_000L, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of range");
        }

        @Test
        void boundsAnOffsetForAPhantomEngineAtTheDefaultCap() {
            assertThatThrownBy(() -> good(Map.of("ghost-engine", 9_999_999))
                            .validateInbound("HASH", "startTime", caps, 600_000L, now))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAChangedFilterOrSort() {
            assertThatThrownBy(() ->
                            good(Map.of("engine-a", 10)).validateInbound("DIFFERENT", "startTime", caps, 600_000L, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does-not-match");
            assertThatThrownBy(() ->
                            good(Map.of("engine-a", 10)).validateInbound("HASH", "failureTime", caps, 600_000L, now))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAnExpiredCursor() {
            PagingCursor old = cursor(now - 700_000L, "HASH", Map.of("engine-a", 10), null, List.of());
            assertThatThrownBy(() -> old.validateInbound("HASH", "startTime", caps, 600_000L, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        void rejectsAnUnsupportedVersion() {
            PagingCursor future =
                    new PagingCursor(999, now, "HASH", "startTime", "desc", Map.of("engine-a", 1), null, List.of());
            assertThatThrownBy(() -> future.validateInbound("HASH", "startTime", caps, 600_000L, now))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /* ---------------- filter binding ---------------- */

    @Nested
    class FilterHash {
        SearchRequest req(List<String> engines, String pdk, String sortBy) {
            return new SearchRequest(
                    engines,
                    List.of(InstanceStatus.ACTIVE),
                    pdk,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    sortBy,
                    50);
        }

        @Test
        void sameFilterSameHash() {
            assertThat(PagingCursor.filterHash(req(List.of("a", "b"), "k", "startTime")))
                    .isEqualTo(PagingCursor.filterHash(req(List.of("a", "b"), "k", "startTime")));
        }

        @Test
        void orSetOrderDoesNotChangeTheHash() {
            assertThat(PagingCursor.filterHash(req(List.of("a", "b"), "k", "startTime")))
                    .isEqualTo(PagingCursor.filterHash(req(List.of("b", "a"), "k", "startTime")));
        }

        @Test
        void aChangedFilterChangesTheHash() {
            String base = PagingCursor.filterHash(req(List.of("a"), "k", "startTime"));
            assertThat(base).isNotEqualTo(PagingCursor.filterHash(req(List.of("a"), "OTHER", "startTime")));
            assertThat(base).isNotEqualTo(PagingCursor.filterHash(req(List.of("a"), "k", "failureTime")));
        }
    }

    /* ---------------- the pure bounded k-way merge ---------------- */

    @Nested
    class MergePage {
        final Map<String, Integer> caps = Map.of("a", 5000, "b", 5000);

        @Test
        void pageOneMergesTwoEnginesIntoGlobalDescOrderAndAdvancesOffsets() {
            // window=2 per engine. A: T5,T3 (of T5,T3,T1)  B: T4,T2 (of T4,T2)
            Map<String, List<ProcessInstanceRow>> emit = new LinkedHashMap<>();
            emit.put("a", List.of(row("a:1", "2026-07-09T10:00:05Z"), row("a:2", "2026-07-09T10:00:03Z")));
            emit.put("b", List.of(row("b:1", "2026-07-09T10:00:04Z"), row("b:2", "2026-07-09T10:00:02Z")));
            Map<String, List<String>> raw = new LinkedHashMap<>();
            raw.put("a", List.of("2026-07-09T10:00:05Z", "2026-07-09T10:00:03Z"));
            raw.put("b", List.of("2026-07-09T10:00:04Z", "2026-07-09T10:00:02Z"));
            Map<String, Long> totals = Map.of("a", 3L, "b", 2L);

            PageResult r = PagingCursor.mergePage(null, emit, raw, totals, 2, "startTime", "H", caps, 999L);

            assertThat(r.rows()).extracting(ProcessInstanceRow::compositeId).containsExactly("a:1", "b:1");
            assertThat(r.nextCursor()).isNotNull();
            // a: consumed T5 (offset 1). b: consumed the boundary row b:1 it emitted (offset 1).
            assertThat(r.nextCursor().offsets()).containsEntry("a", 1).containsEntry("b", 1);
            assertThat(r.nextCursor().boundaryKey()).isEqualTo("2026-07-09T10:00:04Z");
            assertThat(r.nextCursor().boundaryIds()).containsExactly("b:1");
            assertThat(r.depthCapped()).isFalse();
        }

        @Test
        void aFullThreePageWalkEmitsEveryRowExactlyOnceInGlobalOrder() {
            // Full ordered streams; window=2. A: T5,T3,T1  B: T4,T2   global: a1,b1,a2,b2,a3
            List<ProcessInstanceRow> a = List.of(
                    row("a:1", "2026-07-09T10:00:05Z"),
                    row("a:2", "2026-07-09T10:00:03Z"),
                    row("a:3", "2026-07-09T10:00:01Z"));
            List<ProcessInstanceRow> b =
                    List.of(row("b:1", "2026-07-09T10:00:04Z"), row("b:2", "2026-07-09T10:00:02Z"));
            Map<String, Long> totals = Map.of("a", 3L, "b", 2L);

            List<String> seen = new ArrayList<>();
            PagingCursor cur = null;
            for (int page = 0; page < 6; page++) {
                Map<String, List<ProcessInstanceRow>> emit = new LinkedHashMap<>();
                Map<String, List<String>> raw = new LinkedHashMap<>();
                emit.put("a", windowRows(a, offset(cur, "a"), 2));
                emit.put("b", windowRows(b, offset(cur, "b"), 2));
                raw.put("a", windowKeys(a, offset(cur, "a"), 2));
                raw.put("b", windowKeys(b, offset(cur, "b"), 2));
                PageResult r = PagingCursor.mergePage(cur, emit, raw, totals, 2, "startTime", "H", caps, 1L);
                r.rows().forEach(row -> seen.add(row.compositeId()));
                if (r.nextCursor() == null) break;
                cur = r.nextCursor();
            }
            // Every row, exactly once, in the correct global descending order — no dup, no skip.
            assertThat(seen).containsExactly("a:1", "b:1", "a:2", "b:2", "a:3");
        }

        @Test
        void aSameInstantTieClusterStraddlingThePageBoundaryIsDrainedWithoutDupOrSkip() {
            // Five rows share the SAME instant; compositeId tiebreak orders them; window=2.
            String T = "2026-07-09T10:00:00Z";
            List<ProcessInstanceRow> a = List.of(row("a:1", T), row("a:2", T), row("a:3", T));
            List<ProcessInstanceRow> b = List.of(row("b:1", T), row("b:2", T));
            Map<String, Long> totals = Map.of("a", 3L, "b", 2L);

            List<String> seen = new ArrayList<>();
            PagingCursor cur = null;
            for (int page = 0; page < 10; page++) {
                Map<String, List<ProcessInstanceRow>> emit = new LinkedHashMap<>();
                Map<String, List<String>> raw = new LinkedHashMap<>();
                emit.put("a", windowRows(a, offset(cur, "a"), 2));
                emit.put("b", windowRows(b, offset(cur, "b"), 2));
                raw.put("a", windowKeys(a, offset(cur, "a"), 2));
                raw.put("b", windowKeys(b, offset(cur, "b"), 2));
                PageResult r = PagingCursor.mergePage(cur, emit, raw, totals, 2, "startTime", "H", caps, 1L);
                r.rows().forEach(row -> seen.add(row.compositeId()));
                if (r.nextCursor() == null) break;
                cur = r.nextCursor();
            }
            // compositeId total order over the whole same-instant cluster, once each.
            assertThat(seen).containsExactly("a:1", "a:2", "a:3", "b:1", "b:2");
        }

        @Test
        void reachingThePerEngineCapFlagsDepthCappedAndClampsTheOffset() {
            Map<String, Integer> tightCaps = Map.of("a", 3);
            // Emit all 4 rows (pageSize 4); boundary = the oldest (T2). All 4 raw rows are >= boundary
            // and all were emitted → advance = 4, clamped to the cap of 3, depthCapped set.
            List<ProcessInstanceRow> rows = List.of(
                    row("a:1", "2026-07-09T10:00:05Z"),
                    row("a:2", "2026-07-09T10:00:04Z"),
                    row("a:3", "2026-07-09T10:00:03Z"),
                    row("a:4", "2026-07-09T10:00:02Z"));
            Map<String, List<ProcessInstanceRow>> emit = Map.of("a", rows);
            Map<String, List<String>> raw =
                    Map.of("a", rows.stream().map(ProcessInstanceRow::startTime).toList());
            PageResult r =
                    PagingCursor.mergePage(null, emit, raw, Map.of("a", 50L), 4, "startTime", "H", tightCaps, 1L);
            assertThat(r.depthCapped()).isTrue();
            // The only engine is walled at its cap → the chain ENDS (no nextCursor); the UI offers the
            // depth-wall "narrow to continue" seam instead of looping on the same capped window.
            assertThat(r.nextCursor()).isNull();
        }

        @Test
        void aWalledEngineDoesNotStallTheChainWhileAnotherEngineStillHasRows() {
            // engine 'a' walled at cap 1; engine 'b' (huge cap) still has rows → the chain continues on
            // b, a stays frozen at its cap, and depthCapped is surfaced.
            Map<String, Integer> mixedCaps = Map.of("a", 1, "b", 5000);
            Map<String, List<ProcessInstanceRow>> emit = new LinkedHashMap<>();
            emit.put("a", List.of(row("a:1", "2026-07-09T10:00:05Z")));
            emit.put("b", List.of(row("b:1", "2026-07-09T10:00:04Z")));
            Map<String, List<String>> raw = new LinkedHashMap<>();
            raw.put("a", List.of("2026-07-09T10:00:05Z", "2026-07-09T10:00:03Z"));
            raw.put("b", List.of("2026-07-09T10:00:04Z", "2026-07-09T10:00:02Z"));
            PageResult r = PagingCursor.mergePage(
                    null, emit, raw, Map.of("a", 9L, "b", 9L), 2, "startTime", "H", mixedCaps, 1L);
            assertThat(r.depthCapped()).isTrue();
            assertThat(r.nextCursor()).isNotNull(); // b still pages
            assertThat(r.nextCursor().offsets()).containsEntry("a", 1); // a frozen at its cap
        }

        @Test
        void anEmptyResultEndsTheStreamWithANullCursor() {
            PageResult r = PagingCursor.mergePage(
                    null,
                    Map.of("a", List.of()),
                    Map.of("a", List.of()),
                    Map.of("a", 0L),
                    10,
                    "startTime",
                    "H",
                    caps,
                    1L);
            assertThat(r.rows()).isEmpty();
            assertThat(r.nextCursor()).isNull();
        }

        private int offset(PagingCursor cur, String engine) {
            return cur == null ? 0 : cur.offsets().getOrDefault(engine, 0);
        }

        private List<ProcessInstanceRow> windowRows(List<ProcessInstanceRow> all, int start, int size) {
            if (start >= all.size()) return List.of();
            return all.subList(start, Math.min(start + size, all.size()));
        }

        private List<String> windowKeys(List<ProcessInstanceRow> all, int start, int size) {
            return windowRows(all, start, size).stream()
                    .map(ProcessInstanceRow::startTime)
                    .toList();
        }
    }
}
