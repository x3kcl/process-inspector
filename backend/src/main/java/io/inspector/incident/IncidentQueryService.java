package io.inspector.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.attention.AttentionScoreService;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.AttentionScore;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.IncidentDetail;
import io.inspector.dto.IncidentListResponse;
import io.inspector.dto.IncidentSummary;
import io.inspector.dto.SelfHealStats;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.security.ReadScopeGate;
import io.inspector.selfheal.SelfHealStatsService;
import io.inspector.triage.ErrorGroupAckService;
import io.inspector.triage.ErrorSignatureNormalizer;
import io.inspector.triage.TriageScopeProjector;
import io.inspector.triage.TriageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Incident Ledger's READ path (R-BAU-10 S2, INCIDENT-LEDGER.md §6): list + detail off the
 * V18 store, VIEWER floor, no mutations. Deliberately NOT gated by
 * {@code inspector.incidents.enabled} — that flag gates INGESTION (the sampler-event listener,
 * {@link IncidentLedgerService}); history already written must stay readable when an operator
 * switches ingestion off, so this service depends only on the repositories and boots in every
 * profile, including the docker-free {@code incidents.enabled=false} test family.
 *
 * <p><b>Scope projection (R-SAFE-17):</b> service-layer, per-request, off the SAME
 * {@link ReadScopeGate} readable-engine set the triage dashboard/trends use ({@code null} =
 * enforcement off = legacy fleet-wide reads, returned verbatim). Each incident's
 * {@code countsByEngine} blob is narrowed to readable engines; zero intersection ⇒ the incident
 * is OMITTED from the list and the detail answers the SAME 404 as an unknown id (never leak
 * existence — the {@code ViewsController} saved-view doctrine, deliberately not a 403). The
 * display-total honesty follows the {@code LeakViewScopeProjector} precedent (recompute, never
 * null — an incident total, unlike {@code ErrorGroup}'s DL/retrying split, decomposes fully per
 * engine): fully in scope carries the stored fleet {@code lastTotal}, partial scope RECOMPUTES
 * the total from surviving engines and sets {@code partial=true}.
 *
 * <p><b>Live join:</b> the detail's {@code live} group is a render-time join against the SAME
 * shared cached aggregation the dashboard serves — {@code triage.dashboard(false)} through
 * {@link TriageScopeProjector} and {@link ErrorGroupAckService#decorate} exactly like
 * {@code GET /api/triage}. Zero engine calls of this service's own: a cold cache runs the
 * shared single-flight aggregate (the dashboard's own cold path), never a second plan.
 */
@Service
public class IncidentQueryService {

    /** Same look-back ceiling as {@code /api/triage/trends}: clamp so no window scans unbounded. */
    static final int MAX_WINDOW_HOURS = 24 * 30;

    private static final TypeReference<Map<String, Map<String, Long>>> COUNTS_SHAPE = new TypeReference<>() {};

    private final IncidentRepository incidents;
    private final IncidentEpisodeRepository episodes;
    private final IncidentOccurrenceRepository occurrences;
    private final ReadScopeGate gate;
    private final TriageService triage;
    private final TriageScopeProjector scopeProjector;
    private final ErrorGroupAckService acks;
    private final RelatedBulkJobsService relatedBulkJobs;
    private final EpisodeActionAttributionService episodeActionAttribution;
    private final SelfHealStatsService selfHeal;
    private final AttentionScoreService attention;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration quietWindow;
    private final int listCap;

    public IncidentQueryService(
            IncidentRepository incidents,
            IncidentEpisodeRepository episodes,
            IncidentOccurrenceRepository occurrences,
            ReadScopeGate gate,
            TriageService triage,
            TriageScopeProjector scopeProjector,
            ErrorGroupAckService acks,
            RelatedBulkJobsService relatedBulkJobs,
            EpisodeActionAttributionService episodeActionAttribution,
            SelfHealStatsService selfHeal,
            AttentionScoreService attention,
            ObjectMapper json,
            Clock clock,
            InspectorProperties properties) {
        this.incidents = incidents;
        this.episodes = episodes;
        this.occurrences = occurrences;
        this.gate = gate;
        this.triage = triage;
        this.scopeProjector = scopeProjector;
        this.acks = acks;
        this.relatedBulkJobs = relatedBulkJobs;
        this.episodeActionAttribution = episodeActionAttribution;
        this.selfHeal = selfHeal;
        this.attention = attention;
        this.json = json;
        this.clock = clock;
        this.quietWindow = properties.incidentsOrDefault().quietWindowOrDefault();
        this.listCap = properties.incidentsOrDefault().listCapOrDefault();
    }

    /**
     * The bounded ledger list, most-recently-seen first. Still unpaginated in v1 BY DESIGN
     * (INCIDENT-LEDGER §6, no page-number/cursor param) — cardinality is distinct failure
     * classes — tens to hundreds — and the client derives its sections
     * (REGRESSED/OPEN/QUIET/RESOLVED, generation split) from the full list. An absent
     * {@code windowHours} still means "the whole ledger", but never truly UNBOUNDED (issue
     * #308): the store fetch is always capped at {@code inspector.incidents.list-cap} + 1 rows so
     * an unbounded query is structurally impossible even when a caller omits the window, and
     * {@link IncidentListResponse#truncated()} tells the truth whenever the cap actually bit.
     * Because the fetch is ordered {@code lastSeen DESC}, a truncation always drops the OLDEST
     * rows — the response stays the freshest possible slice, never an arbitrary one. {@code
     * state} filters case-insensitively (unknown ⇒ 400); {@code windowHours} (optional, clamped
     * to 30 days) keeps only incidents last seen inside the window.
     */
    public IncidentListResponse list(String state, Integer windowHours, Authentication auth) {
        IncidentState filter = parseState(state);
        Set<String> readable = gate.readableEngineIds(auth);
        Instant now = clock.instant();
        Instant since = windowHours != null ? now.minus(Duration.ofHours(clampWindow(windowHours))) : null;
        // Fetch cap+1 so a full page (size == cap+1) tells us there WAS a row beyond the cap,
        // without needing a separate count query. The recency window is still pushed down to
        // the store either way — never an in-memory filter over a potentially huge table.
        PageRequest page = PageRequest.of(0, listCap + 1);
        List<Incident> rows;
        if (since != null) {
            rows = filter != null
                    ? incidents.findByStateAndLastSeenGreaterThanEqualOrderByLastSeenDesc(filter, since, page)
                    : incidents.findAllByLastSeenGreaterThanEqualOrderByLastSeenDesc(since, page);
        } else {
            rows = filter != null
                    ? incidents.findByStateOrderByLastSeenDesc(filter, page)
                    : incidents.findAllByOrderByLastSeenDesc(page);
        }
        boolean truncated = rows.size() > listCap;
        if (truncated) {
            rows = rows.subList(0, listCap);
        }
        List<IncidentSummary> out = new ArrayList<>();
        for (Incident row : rows) {
            IncidentSummary summary = summarize(row, readable, now);
            if (summary != null) {
                out.add(summary);
            }
        }
        return new IncidentListResponse(out, truncated);
    }

    /**
     * One incident: the list-item shape + full episode history (newest first) + the windowed
     * occurrence series + the live Stage-0 join. Unknown id and zero-scope-intersection answer
     * the SAME 404 (see class doc).
     */
    public IncidentDetail detail(long id, int windowHours, Authentication auth) {
        return detail(id, windowHours, null, auth);
    }

    /**
     * The same read with an optional {@code until} CURSOR (#372 §16.8 item 7): the series is
     * {@code [until − clamped, until)} instead of {@code [now − clamped, now]}, so a caller can
     * page BACKWARD past {@link #MAX_WINDOW_HOURS} in bounded chunks. Each call is still
     * time-limited to at most one clamped window — the "no window scans unbounded" property is
     * preserved; only the reachable TOTAL span changes.
     *
     * <p>Why it exists: the amended G5 gate (ALARM-COST-MODEL §16.7) measures the CURRENT-ERA
     * trusted span and needs ≥ 56 d of it, while this surface reaches 30 — so the era boundary a
     * VIEWER must walk back to is structurally unreachable in a single call, and the whole point
     * of shipping {@code fleet} on the wire was to keep that measurement VIEWER/REST-only rather
     * than quietly re-introducing a DB-access dependency. A {@code null} cursor is exactly the
     * pre-#372 call: the most recent clamped window, inclusive of {@code now}.
     */
    public IncidentDetail detail(long id, int windowHours, Instant until, Authentication auth) {
        Set<String> readable = gate.readableEngineIds(auth);
        Instant now = clock.instant();
        Incident row = incidents.findById(id).orElseThrow(IncidentQueryService::notFound);
        IncidentSummary summary = summarize(row, readable, now);
        if (summary == null) {
            throw notFound(); // out of scope == absent: never leak existence (not a 403)
        }
        // Unbounded by design: episode count = 1 + regression_count, and every regression needs a
        // human resolve in between — a pathological ledger has dozens, not thousands.
        List<IncidentEpisode> episodeRows = episodes.findByIncidentIdOrderByStartedAtDesc(id);
        // #358 item 2: the audit-side attribution join, keyed by episode id (never positional —
        // see EpisodeActionAttributionService for why) — a missing key (e.g. an unstubbed test
        // double, or a degrade-safe short-circuit) renders as an honestly-omitted NON_NULL field.
        Map<Long, IncidentDetail.EpisodeActionAttribution> attributions =
                episodeActionAttribution.forEpisodes(row, episodeRows);
        List<IncidentDetail.Episode> history = episodeRows.stream()
                .map(episode -> toEpisode(episode, attributions.get(episode.getId())))
                .toList();
        int clamped = clampWindow(windowHours);
        Duration window = Duration.ofHours(clamped);
        // No cursor ⇒ the pre-#372 read, unchanged: [now − clamped, now]. With one ⇒ the
        // half-open page [until − clamped, until), so chained calls never repeat a row at the seam.
        List<IncidentOccurrence> rows = until == null
                ? occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(
                        id, now.minus(window))
                : occurrences
                        .findByIdIncidentIdAndIdSampledAtGreaterThanEqualAndIdSampledAtLessThanOrderByIdSampledAtAsc(
                                id, until.minus(window), until);
        List<IncidentDetail.OccurrencePoint> series = rows.stream()
                .map(point -> new IncidentDetail.OccurrencePoint(
                        point.getId().getSampledAt(),
                        point.getTotal(),
                        point.getDeadLetterCount(),
                        point.getRetryingCount(),
                        point.isTruncated(),
                        point.isCycleComplete(),
                        point.getFleet()))
                .toList();
        return new IncidentDetail(
                summary,
                history,
                Duration.ofHours(clamped).toString(),
                series,
                liveGroup(row, auth),
                // S5: read-only remediation join, scope-narrowed too (R-SAFE-17, issue #329) —
                // the SAME readable set resolved above for the incident's own projection, since a
                // signature can span an engine the caller can read and one they cannot even when
                // the incident itself clears the zero-intersection gate.
                relatedBulkJobs.forSignature(row.getSignatureHash(), row.getAlgoVersion(), readable));
    }

    /* ---------------- projection ---------------- */

    /**
     * The S3 seam: one incident through the SAME per-request scope projection the list/detail
     * use, or {@code null} when the caller's readable-engine set intersects none of its engines
     * — the lifecycle verbs gate on this so a scoped OPERATOR cannot resolve/reopen (or even
     * confirm the existence of) an incident they cannot see, answering the identical 404.
     */
    public IncidentSummary projectForCaller(Incident row, Authentication auth) {
        return summarize(row, gate.readableEngineIds(auth), clock.instant());
    }

    /**
     * The scope-projected list-item shape, or {@code null} when the incident holds no readable
     * engine (zero intersection — omitted, never partially leaked). Honesty rules in class doc.
     */
    private IncidentSummary summarize(Incident row, Set<String> readable, Instant now) {
        Map<String, Map<String, Long>> full = parseCounts(row.getCountsByEngine());
        Map<String, Map<String, Long>> scoped = full;
        boolean partial = false;
        long total = row.getLastTotal();
        if (readable != null) {
            Map<String, Map<String, Long>> narrowed = new LinkedHashMap<>();
            full.forEach((engineId, byDefVersion) -> {
                if (readable.contains(engineId)) {
                    narrowed.put(engineId, byDefVersion);
                }
            });
            if (narrowed.isEmpty()) {
                return null;
            }
            partial = narrowed.size() < full.size();
            if (partial) {
                // Recomputed from survivors — the fleet total would overstate the caller's slice.
                total = narrowed.values().stream()
                        .flatMap(byDefVersion -> byDefVersion.values().stream())
                        .mapToLong(Long::longValue)
                        .sum();
            }
            scoped = narrowed;
        }
        SelfHealStats stats = selfHealStats(row);
        return new IncidentSummary(
                row.getId(),
                row.getSignatureHash(),
                row.getAlgoVersion(),
                row.getAlgoVersion() == ErrorSignatureNormalizer.ALGO_VERSION,
                row.getExceptionClass(),
                row.getNormalizedMessage(),
                row.getSampleRawMessage(),
                row.getState().name(),
                row.getFirstSeen(),
                row.getLastSeen(),
                row.getLastSeen().isBefore(now.minus(quietWindow)),
                total,
                row.isLastTruncated(),
                scoped,
                partial,
                row.getRegressionCount(),
                row.getLastRegressedAt(),
                stats,
                attentionScore(row, total, stats));
    }

    /**
     * The R1 attention score (ALARM-COST-MODEL.md §4, #353) — absent unless
     * {@code inspector.triage.attention-ordering} is on. Ordering INPUT only: the list keeps its
     * {@code lastSeen DESC} server order (the #308 hard cap must drop the OLDEST rows) and its
     * client-derived REGRESSED/OPEN/QUIET/RESOLVED sections; the score orders within the live
     * sections, where they are actually formed. The SCOPED total is what gets explained — a
     * partially-scoped caller's rationale must never quote the fleet number (R-SAFE-17).
     */
    private AttentionScore attentionScore(Incident row, long scopedTotal, SelfHealStats stats) {
        return attention.forClass(row.getSignatureHash(), row.getAlgoVersion(), scopedTotal, stats);
    }

    /**
     * The RETRYING risk lane's per-class decoration (RETRYING-RISK-LANE.md, #351) — informational
     * only, never gates the projection above. Degrade-safe: the self-heal surface never breaks
     * the incident read it decorates.
     */
    private SelfHealStats selfHealStats(Incident row) {
        try {
            return selfHeal.get(row.getSignatureHash(), row.getAlgoVersion());
        } catch (RuntimeException e) {
            return null; // NON_NULL omits it on the wire — an incident read must never 500 for this
        }
    }

    /**
     * The dashboard's own read path verbatim (shared cache → per-request scope projection → live
     * ack decoration), then pick this incident's {@code (signatureHash, algoVersion)} group.
     * Absent (drained, retired generation, or scoped away) ⇒ {@code null} ⇒ omitted on the wire.
     */
    private ErrorGroup liveGroup(Incident row, Authentication auth) {
        TriageDashboardResponse dashboard = acks.decorate(scopeProjector.project(triage.dashboard(false), auth));
        List<ErrorGroup> groups = dashboard.errorGroups() != null ? dashboard.errorGroups() : List.of();
        return groups.stream()
                .filter(g ->
                        g.signatureHash().equals(row.getSignatureHash()) && g.algoVersion() == row.getAlgoVersion())
                .findFirst()
                .orElse(null);
    }

    /* ---------------- helpers ---------------- */

    private static IncidentDetail.Episode toEpisode(
            IncidentEpisode episode, IncidentDetail.EpisodeActionAttribution attribution) {
        Long durationSeconds = episode.getEndedAt() != null
                ? Duration.between(episode.getStartedAt(), episode.getEndedAt()).toSeconds()
                : null;
        return new IncidentDetail.Episode(
                episode.getId(),
                episode.getStartState().name(),
                episode.getStartedAt(),
                episode.getEndedAt(),
                episode.getResolvedBy(),
                episode.getResolveReason(),
                episode.getTicketId(),
                episode.getPeakTotal(),
                durationSeconds,
                attribution);
    }

    private static IncidentState parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return IncidentState.valueOf(state.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "state must be one of OPEN, RESOLVED or REGRESSED");
        }
    }

    private static int clampWindow(int hours) {
        return Math.max(1, Math.min(hours, MAX_WINDOW_HOURS));
    }

    private Map<String, Map<String, Long>> parseCounts(String countsByEngine) {
        try {
            Map<String, Map<String, Long>> parsed = json.readValue(countsByEngine, COUNTS_SHAPE);
            return parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException e) {
            // The ledger's own writer serialized this blob — a parse failure is store corruption
            // and should be LOUD, but still inside the one-error-contract (RFC-7807, not a bare
            // 500): ResponseStatusException routes through the shared handler.
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "incident store corrupted: counts_by_engine unreadable", e);
        }
    }

    /** Package-visible so the S3 lifecycle verbs answer the byte-identical 404 (no existence leak). */
    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such incident");
    }
}
