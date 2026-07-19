package io.inspector.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.IncidentDetail;
import io.inspector.dto.IncidentSummary;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.security.ReadScopeGate;
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
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration quietWindow;

    public IncidentQueryService(
            IncidentRepository incidents,
            IncidentEpisodeRepository episodes,
            IncidentOccurrenceRepository occurrences,
            ReadScopeGate gate,
            TriageService triage,
            TriageScopeProjector scopeProjector,
            ErrorGroupAckService acks,
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
        this.json = json;
        this.clock = clock;
        this.quietWindow = properties.incidentsOrDefault().quietWindowOrDefault();
    }

    /**
     * The bounded ledger list, most-recently-seen first. No pagination in v1 BY DESIGN
     * (INCIDENT-LEDGER §6): cardinality is distinct failure classes — tens to hundreds — and the
     * client derives its sections (REGRESSED/OPEN/QUIET/RESOLVED, generation split) from the
     * full list. {@code state} filters case-insensitively (unknown ⇒ 400); {@code windowHours}
     * (optional, clamped to 30 days) keeps only incidents last seen inside the window.
     */
    public List<IncidentSummary> list(String state, Integer windowHours, Authentication auth) {
        IncidentState filter = parseState(state);
        Set<String> readable = gate.readableEngineIds(auth);
        Instant now = clock.instant();
        List<Incident> rows = filter != null
                ? incidents.findByStateOrderByLastSeenDesc(filter)
                : incidents.findAllByOrderByLastSeenDesc();
        Instant since = windowHours != null ? now.minus(Duration.ofHours(clampWindow(windowHours))) : null;
        List<IncidentSummary> out = new ArrayList<>();
        for (Incident row : rows) {
            if (since != null && row.getLastSeen().isBefore(since)) {
                continue;
            }
            IncidentSummary summary = summarize(row, readable, now);
            if (summary != null) {
                out.add(summary);
            }
        }
        return out;
    }

    /**
     * One incident: the list-item shape + full episode history (newest first) + the windowed
     * occurrence series + the live Stage-0 join. Unknown id and zero-scope-intersection answer
     * the SAME 404 (see class doc).
     */
    public IncidentDetail detail(long id, int windowHours, Authentication auth) {
        Set<String> readable = gate.readableEngineIds(auth);
        Instant now = clock.instant();
        Incident row = incidents.findById(id).orElseThrow(IncidentQueryService::notFound);
        IncidentSummary summary = summarize(row, readable, now);
        if (summary == null) {
            throw notFound(); // out of scope == absent: never leak existence (not a 403)
        }
        List<IncidentDetail.Episode> history = episodes.findByIncidentIdOrderByStartedAtDesc(id).stream()
                .map(IncidentQueryService::toEpisode)
                .toList();
        int clamped = clampWindow(windowHours);
        List<IncidentDetail.OccurrencePoint> series = occurrences
                .findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(
                        id, now.minus(Duration.ofHours(clamped)))
                .stream()
                .map(point -> new IncidentDetail.OccurrencePoint(
                        point.getId().getSampledAt(),
                        point.getTotal(),
                        point.getDeadLetterCount(),
                        point.getRetryingCount(),
                        point.isTruncated()))
                .toList();
        return new IncidentDetail(summary, history, Duration.ofHours(clamped).toString(), series, liveGroup(row, auth));
    }

    /* ---------------- projection ---------------- */

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
                row.getLastRegressedAt());
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

    private static IncidentDetail.Episode toEpisode(IncidentEpisode episode) {
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
                durationSeconds);
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
            // The ledger's own writer serialized this blob — a parse failure is store corruption.
            throw new IllegalStateException("counts_by_engine deserialization failed", e);
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such incident");
    }
}
