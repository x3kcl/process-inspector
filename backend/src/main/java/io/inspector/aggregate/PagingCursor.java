package io.inspector.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchRequest.VariableFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The stateless, opaque deep-paging cursor (R-SEM-22, {@code docs/KWAY-PAGING.md} §4) and the pure
 * bounded k-way page-merge that advances it. {@code base64url(JSON)}, read-only and idempotent;
 * the authoritative filter is ALWAYS the request body, never the cursor. {@code filterHash} only
 * <em>fails</em> a mismatched reuse (400 {@code cursor-does-not-match}) — it gives NO integrity
 * against a crafted cursor, so decoded offsets are re-validated against the per-engine depth cap
 * (R-NFR-08) BEFORE any fan-out ({@link #validateInbound}); that inbound bound-check — not HMAC —
 * is the DoS ceiling.
 *
 * <p><strong>Tagged union by plan (§4):</strong> only the HISTORIC / MIXED ({@code startTime desc})
 * variant exists. The INVERTED / {@code failureTime} plan scans the DLQ unsorted and sorts on a
 * BFF-derived key, so it has no engine-side resume position — deep paging is MIXED-first and
 * INVERTED is gated off (the overflow banner offers the depth-wall filter seam instead).
 *
 * <p><strong>Honesty (R-SEM-22):</strong> the merge bounds duplicate <em>emission</em> to one
 * sort-key value via {@code boundaryKey}+{@code boundaryIds}; it does NOT repair an engine-side
 * page-boundary skip under concurrent mutation. Deterministic for a fixed engine response; never
 * claimed stable across a straddling insert/delete.
 */
public record PagingCursor(
        int v,
        long issuedAt,
        String filterHash,
        String sortBy,
        String order,
        Map<String, Integer> offsets,
        String boundaryKey,
        List<String> boundaryIds) {

    /** Bumped whenever the wire shape changes; an old cursor is rejected, never mis-read. */
    public static final int VERSION = 1;

    /** R-NFR-01: at most 10 engines per fan-out — a cursor naming more is malformed. */
    private static final int MAX_ENGINES = 10;

    /** Fallback per-engine cap for an offset naming an engine not among the current targets. */
    static final int DEFAULT_DEPTH_CAP = 5000;

    /**
     * Bound on {@code boundaryIds} (the same-key dedup set) — keeps the in-token cursor small. The
     * §4 documented fallback: a single-instant cluster on one engine larger than this (astronomically
     * rare — {@code >512} instances started in the SAME millisecond) may re-emit a bounded duplicate
     * at exactly that instant; S3's {@code pagingCoherence} surfaces it. Never a silent skip.
     */
    static final int MAX_BOUNDARY_IDS = 512;

    /** Reject an over-long token before decoding it — a cheap guard before the base64/JSON parse. */
    private static final int MAX_TOKEN_CHARS = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /** {@code base64url(JSON)}. */
    public String encode() {
        try {
            return B64.encodeToString(JSON.writeValueAsBytes(this));
        } catch (Exception e) {
            // Fields are plain scalars/collections — serialization cannot fail in practice.
            throw new IllegalStateException("cursor encode failed", e);
        }
    }

    /** Decode an opaque cursor; malformed/garbage input → {@link IllegalArgumentException} (→ 400). */
    public static PagingCursor decode(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("cursor is empty");
        }
        if (token.length() > MAX_TOKEN_CHARS) {
            // Bound the pre-validation work: a giant token can't be a cursor we issued (boundaryIds
            // is capped), so reject it before spending base64/JSON parse cycles on it.
            throw new IllegalArgumentException("cursor is malformed");
        }
        try {
            byte[] json = B64D.decode(token);
            PagingCursor c = JSON.readValue(json, PagingCursor.class);
            if (c == null || c.offsets == null) {
                throw new IllegalArgumentException("cursor is malformed");
            }
            return c;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("cursor is malformed", e);
        }
    }

    /**
     * Reject (400) a cursor that cannot be honoured, BEFORE any engine is touched (R-NFR-08). This
     * is the DoS ceiling: an attacker controls both the filter and its hash, so the hash can only
     * fail a mismatched reuse — the load-bearing guard is the per-engine offset bound-check that
     * stops a crafted {@code offsets} map from steering an O(huge-offset) scan across ≤10 engines.
     */
    public void validateInbound(
            String expectedFilterHash,
            String expectedSortBy,
            Map<String, Integer> depthCaps,
            long ttlMillis,
            long nowMillis) {
        if (v != VERSION) {
            throw new IllegalArgumentException("cursor version unsupported — start over");
        }
        if (!expectedSortBy.equals(sortBy) || !Objects.equals(expectedFilterHash, filterHash)) {
            // The filter (or sort) changed under the cursor — the offsets no longer mean anything.
            throw new IllegalArgumentException("cursor-does-not-match the current search — start over");
        }
        if (nowMillis - issuedAt > ttlMillis) {
            throw new IllegalArgumentException("cursor-expired — start over");
        }
        if (offsets.size() > MAX_ENGINES) {
            throw new IllegalArgumentException("cursor names too many engines");
        }
        // Per-engine bound-check — an engine not among the current targets is capped at the default
        // so a crafted offset for a phantom engine is still bounded (it just won't be fanned out to).
        for (Map.Entry<String, Integer> e : offsets.entrySet()) {
            int cap = depthCaps.getOrDefault(e.getKey(), DEFAULT_DEPTH_CAP);
            Integer off = e.getValue();
            if (off == null || off < 0 || off > cap) {
                throw new IllegalArgumentException(
                        "cursor offset for '" + e.getKey() + "' is out of range (0.." + cap + ")");
            }
        }
    }

    /* ---------------- filter binding ---------------- */

    /**
     * A stable hash over every field that defines the result SET and its ORDER (so a cursor
     * reused against a changed filter fails the {@link #validateInbound} bind). OR-set fields are
     * order-normalized; the cursor never carries the filter, only this fingerprint of it.
     */
    public static String filterHash(SearchRequest req) {
        StringBuilder sb = new StringBuilder(256);
        line(sb, "eng", sortedCsv(req.engineIds()));
        line(sb, "st", statusCsv(req.effectiveStatuses()));
        line(sb, "pdk", req.processDefinitionKey());
        line(sb, "dv", req.definitionVersion());
        line(sb, "bk", req.businessKey());
        line(sb, "bkl", req.businessKeyLike());
        line(sb, "sa", req.startedAfter());
        line(sb, "sb", req.startedBefore());
        line(sb, "fa", req.failureTimeAfter());
        line(sb, "fb", req.failureTimeBefore());
        line(sb, "et", req.errorText());
        line(sb, "sh", req.signatureHash());
        line(sb, "ca", req.currentActivity());
        line(sb, "vars", variablesCanon(req.variables()));
        line(sb, "sort", req.sortBy());
        line(sb, "ps", req.pageSize());
        return sha256Hex(sb.toString());
    }

    private static void line(StringBuilder sb, String key, Object value) {
        sb.append(key).append('=').append(value == null ? "" : value.toString()).append('\n');
    }

    private static String sortedCsv(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private static String statusCsv(List<InstanceStatus> statuses) {
        return statuses.stream()
                .map(Enum::name)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /** Variable filters ANDed into the query — order-insensitive, so normalize by a stable key. */
    private static String variablesCanon(List<VariableFilter> vars) {
        if (vars == null || vars.isEmpty()) return "";
        // TreeMap keys sort; value/operation/type folded in so two different filters never collide.
        Map<String, String> canon = new TreeMap<>();
        int i = 0;
        for (VariableFilter v : vars) {
            String signature = v.name() + "|" + v.operation() + "|" + v.type() + "|" + v.value();
            canon.put(signature + "#" + i++, "");
        }
        return String.join(";", canon.keySet());
    }

    private static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /* ---------------- the pure bounded k-way page merge ---------------- */

    /**
     * The outcome of one deep page: the emitted rows, the cursor for the NEXT page (null at end),
     * whether any engine hit its depth cap, and (#273) exactly WHICH engines are walled at their
     * cap this page — the per-lane counterpart to the aggregate {@code depthCapped} flag, so the
     * UI can tell "this specific lane will never grow past its current fetched count" apart from
     * "this lane just has more pages left, click Load more again".
     */
    public record PageResult(
            List<ProcessInstanceRow> rows, PagingCursor nextCursor, boolean depthCapped, Set<String> cappedEngines) {}

    /**
     * Every engine in play this page: the ones with a raw window (even an empty one — the map
     * key alone proves it was targeted) plus any the incoming cursor already carries an offset
     * for. Shared by the emitCount==0 walled check and step 5's offset advance (#177) so the two
     * can never compute a different engine set for the same merge call.
     */
    private static Set<String> engineSet(Map<String, List<String>> rawWindowKeys, Map<String, Integer> baseOffsets) {
        Set<String> engines = new HashSet<>(rawWindowKeys.keySet());
        engines.addAll(baseOffsets.keySet());
        return engines;
    }

    /**
     * Merge one bounded window per engine into the next global page, in the R-SEM-23 total order,
     * and produce the cursor that resumes AFTER it. Pure — no engine I/O.
     *
     * <p>Correctness sketch (why it neither dups nor skips on a stable engine, and stays bounded
     * on an unstable one): each engine returns its window in descending-key order from its
     * {@code start=offset}. We drop rows already emitted on earlier pages (key strictly newer than
     * the incoming {@code boundaryKey}, or exactly at it and in {@code boundaryIds}), merge the
     * rest with the S1 comparator, and emit the top {@code pageSize}. The next offset advances
     * ONLY past rows strictly newer than the NEW boundary key — never past the boundary tie-cluster
     * itself, which is re-fetched next page and id-deduped via {@code boundaryIds}. So a tie cluster
     * straddling a page boundary is drained by id, independent of the engine's (unstable) intra-key
     * order. {@code boundaryIds} accumulates while the boundary key is unchanged, bounded by
     * {@link #MAX_BOUNDARY_IDS} (the §4 documented fallback: a single-key cluster larger than that
     * may emit a bounded duplicate — labeled, never silently wrong).
     *
     * @param incoming       the decoded cursor for pages ≥2, or {@code null} for the first deep page
     * @param emittable      per-engine window rows that PASSED the BFF post-filters + enrichment,
     *                       each in the engine's returned (descending) order — the merge/emit input
     * @param rawWindowKeys  per-engine sort-key of EVERY raw engine row in the window (including the
     *                       ones the BFF filtered out), in engine order — the offset-advance basis,
     *                       because the engine paginates its RAW result set, not the filtered subset
     * @param engineTotals   per-engine total matching count (for the "more pages?" decision)
     * @param pageSize       the GLOBAL number of rows to emit this page
     * @param sortBy         the sort key ({@code startTime} | {@code failureTime})
     * @param filterHash     the current request's filter fingerprint, stamped into {@code nextCursor}
     * @param depthCaps      per-engine depth cap (an engine reaching it sets {@code depthCapped})
     * @param nowMillis      {@code issuedAt} for the emitted {@code nextCursor} (TTL clock)
     */
    public static PageResult mergePage(
            PagingCursor incoming,
            Map<String, List<ProcessInstanceRow>> emittable,
            Map<String, List<String>> rawWindowKeys,
            Map<String, Long> engineTotals,
            int pageSize,
            String sortBy,
            String filterHash,
            Map<String, Integer> depthCaps,
            long nowMillis) {

        Map<String, Integer> baseOffsets =
                incoming != null && incoming.offsets() != null ? incoming.offsets() : Map.of();
        Instant incomingBoundary = incoming != null ? StatusJoin.parseInstant(incoming.boundaryKey()) : null;
        Set<String> boundarySeen =
                incoming != null && incoming.boundaryIds() != null ? Set.copyOf(incoming.boundaryIds()) : Set.of();

        // 1. dedup already-emitted rows, keep the rest.
        List<ProcessInstanceRow> kept = new ArrayList<>();
        for (Map.Entry<String, List<ProcessInstanceRow>> entry : emittable.entrySet()) {
            for (ProcessInstanceRow r : entry.getValue()) {
                Instant k = key(r, sortBy);
                boolean newerThanBoundary = incomingBoundary != null && k != null && k.isAfter(incomingBoundary);
                boolean atBoundaryAlreadySeen =
                        Objects.equals(k, incomingBoundary) && boundarySeen.contains(r.compositeId());
                if (!newerThanBoundary && !atBoundaryAlreadySeen) {
                    kept.add(r);
                }
            }
        }

        // 2. the R-SEM-23 total order over the surviving candidates.
        kept.sort(StatusJoin.resultOrder(sortBy));

        // 3. emit the top pageSize.
        int emitCount = Math.min(Math.max(pageSize, 0), kept.size());
        if (emitCount == 0) {
            // Nothing new — end of the stream, but WHY matters (#167): a caller re-fetching an
            // engine already walled at its cap (the prior page clamped its offset there) lands
            // here just as often as a genuine every-engine exhaustion, and the UI can't tell
            // "you've truly seen everything" from "narrow your search to see the rest" without
            // depthCapped. No new rows means no offset advances past this page, so the walled
            // check reuses engineSet(...) — the SAME engine-set helper step 5 calls below —
            // so the two can't drift apart in a future edit (Copilot review, #177).
            boolean anyEngineWalled = false;
            Set<String> walled = new HashSet<>();
            for (String eng : engineSet(rawWindowKeys, baseOffsets)) {
                int cap = depthCaps.getOrDefault(eng, DEFAULT_DEPTH_CAP);
                if (baseOffsets.getOrDefault(eng, 0) >= cap) {
                    anyEngineWalled = true;
                    walled.add(eng);
                }
            }
            return new PageResult(List.of(), null, anyEngineWalled, walled);
        }
        List<ProcessInstanceRow> emitted = new ArrayList<>(kept.subList(0, emitCount));

        // 4. the new boundary = the last emitted row's key; boundaryIds accumulate while it holds.
        Instant lastKey = key(emitted.get(emitCount - 1), sortBy);
        String newBoundaryKey = lastKey != null ? lastKey.toString() : null;
        List<String> newBoundaryIds = new ArrayList<>();
        if (incoming != null
                && Objects.equals(newBoundaryKey, incoming.boundaryKey())
                && incoming.boundaryIds() != null) {
            newBoundaryIds.addAll(incoming.boundaryIds());
        }
        for (ProcessInstanceRow r : emitted) {
            if (Objects.equals(key(r, sortBy), lastKey)) newBoundaryIds.add(r.compositeId());
        }
        if (newBoundaryIds.size() > MAX_BOUNDARY_IDS) {
            // §4 fallback: keep the most-recently-emitted ids — a same-instant cluster on one engine
            // bigger than the bound may re-emit a bounded duplicate at exactly that instant (S3's
            // pagingCoherence surfaces it). NEVER a silent skip: dedup only weakens within one key.
            newBoundaryIds = new ArrayList<>(
                    newBoundaryIds.subList(newBoundaryIds.size() - MAX_BOUNDARY_IDS, newBoundaryIds.size()));
        }

        // 5. advance each engine's offset. It must land on the FIRST raw row not yet accounted for:
        //    every raw row with key >= the new boundary is consumed (emitted, filtered-out, or an
        //    already-emitted boundary row) EXCEPT the boundary-key rows we KEPT but did not emit this
        //    page — those are the next page's work and must stay in the re-fetch zone. So
        //    advance = |raw rows with key >= boundary| − |kept-but-unemitted rows at the boundary|.
        //    This slides cleanly through a same-instant cluster larger than the window (a strictly-
        //    newer-only advance would stall on a mono-instant run and skip its tail).
        Map<String, Integer> keptAtBoundary = new java.util.HashMap<>();
        Map<String, Integer> emittedAtBoundary = new java.util.HashMap<>();
        for (ProcessInstanceRow r : kept) {
            if (Objects.equals(key(r, sortBy), lastKey)) keptAtBoundary.merge(r.engineId(), 1, Integer::sum);
        }
        for (ProcessInstanceRow r : emitted) {
            if (Objects.equals(key(r, sortBy), lastKey)) emittedAtBoundary.merge(r.engineId(), 1, Integer::sum);
        }
        Set<String> engines = engineSet(rawWindowKeys, baseOffsets);
        Map<String, Integer> newOffsets = new java.util.HashMap<>();
        boolean depthCapped = false;
        Set<String> cappedEngines = new HashSet<>();
        for (String eng : engines) {
            int rawGeBoundary = 0;
            if (lastKey != null) {
                for (String rawKey : rawWindowKeys.getOrDefault(eng, List.of())) {
                    Instant k = StatusJoin.parseInstant(rawKey);
                    if (k != null && !k.isBefore(lastKey)) rawGeBoundary++; // key >= boundary
                }
            }
            int keptUnemitted = keptAtBoundary.getOrDefault(eng, 0) - emittedAtBoundary.getOrDefault(eng, 0);
            int advance = Math.max(0, rawGeBoundary - keptUnemitted);
            int cap = depthCaps.getOrDefault(eng, DEFAULT_DEPTH_CAP);
            int next = baseOffsets.getOrDefault(eng, 0) + advance;
            if (next >= cap) {
                next = cap;
                depthCapped = true;
                cappedEngines.add(eng);
            }
            newOffsets.put(eng, next);
        }

        // 6. more pages? — an engine that still has rows past its new offset AND has not yet hit its
        //    depth cap. A WALLED engine (offset clamped at the cap) contributes to depthCapped — the
        //    depth-wall filter seam — NOT to "more": counting it would loop forever re-fetching the
        //    same capped window (nextCursor never null). When every more-having engine is walled the
        //    chain ends with nextCursor=null + depthCapped=true, and the UI offers "narrow to continue".
        boolean more = false;
        for (String eng : engines) {
            int cap = depthCaps.getOrDefault(eng, DEFAULT_DEPTH_CAP);
            int next = newOffsets.get(eng);
            if (next < cap && next < engineTotals.getOrDefault(eng, 0L)) {
                more = true;
                break;
            }
        }

        PagingCursor next = more
                ? new PagingCursor(
                        VERSION, nowMillis, filterHash, sortBy, "desc", newOffsets, newBoundaryKey, newBoundaryIds)
                : null;
        return new PageResult(emitted, next, depthCapped, cappedEngines);
    }

    private static Instant key(ProcessInstanceRow r, String sortBy) {
        return StatusJoin.parseInstant("failureTime".equals(sortBy) ? r.failureTime() : r.startTime());
    }
}
