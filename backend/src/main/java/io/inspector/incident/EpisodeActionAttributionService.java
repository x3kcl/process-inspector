package io.inspector.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.inspector.audit.AttributedActionPoint;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.IncidentDetail;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Episode-level corrective-action attribution (issue #358 item 2 — closes the gap
 * RETRYING-RISK-LANE.md §3.3/§10 named "the OPEN gap the design recorded": "per-class
 * intervention outcomes are currently underivable from any read API — audit rows carry
 * instanceId but no signature, and payload is null over REST per R-AUD-03 minimization").
 *
 * <p><b>Shares #351's path, exactly as the issue asks.</b> {@code SelfHealStatsService}'s spell
 * confound detection already proved the audit-side join this needs: engine + timestamp, read as
 * a constructor-projection so {@code payload} never enters the SELECT ({@link
 * AuditEntryRepository#findSuccessfulRetryJobPoints}, {@link
 * io.inspector.audit.RetryAuditPoint}). This service reuses the identical shape ({@link
 * AuditEntryRepository#findAttributableActionPoints}, {@link AttributedActionPoint}), widened
 * from "successful {@code retry-job} only, ±2 sampler buckets around a spell" to "every
 * corrective-action verb and outcome, inside an episode's own precise {@code [startedAt,
 * endedAt-or-now]} boundaries" — the episode is already signature-keyed (one {@code incident} row
 * per {@code (signature_hash, algo_version)}), so attributing an action to the episode IS
 * attributing it to the class, at the coarser (episode, not instance) grain the issue's own
 * proposed work asks for. Zero new engine calls, zero changes to {@code CorrectiveActionService}
 * or any guard/RBAC/audit-ordering rail — this reads the SAME audit golden master {@code
 * RelatedBulkJobsService} and {@code SelfHealStatsService} already read.
 *
 * <p><b>No de-minimization.</b> R-AUD-03 forbids exposing the audit payload over REST; this
 * service never reads it (the projection query structurally cannot select it) and never exposes
 * anything finer than an aggregate verb/outcome tally per episode — see {@link
 * IncidentDetail.EpisodeActionAttribution}'s javadoc for the honest limitations that follow from
 * staying audit-side instead of stamping a signature into a new per-action column.
 *
 * <p>Read-only, informational: exactly one consumer ({@code IncidentQueryService}, decorating
 * {@code IncidentDetail.Episode}). Degrade-safe like the self-heal decoration it borrows the
 * pattern from — a corrupted {@code counts_by_engine} blob drops attribution for that ONE
 * incident rather than 500ing the detail read it decorates.
 *
 * <p><b>Caffeine-cached, TTL aligned to the sampler beat</b> (review round; the {@code
 * SelfHealStatsService} precedent, §3.2's "acceptable simple implementation" over a bespoke
 * cache key): a per-episode scan is otherwise UNCACHED on a polled surface — a weeks-old,
 * never-closed pilot episode would re-scan its full span on every incident detail render.
 * Keyed by episode id alone (globally unique, one incident forever by FK — no cross-incident
 * collision risk); a late-arriving audit row (e.g. a bulk item settling after its envelope)
 * is visible again within one TTL, same staleness bound the self-heal confound cache accepts.
 */
@Service
public class EpisodeActionAttributionService {

    private static final Logger log = LoggerFactory.getLogger(EpisodeActionAttributionService.class);
    private static final TypeReference<Map<String, Map<String, Long>>> COUNTS_SHAPE = new TypeReference<>() {};

    private static final IncidentDetail.EpisodeActionAttribution EMPTY =
            new IncidentDetail.EpisodeActionAttribution(0, Map.of(), Map.of(), false);

    /**
     * Hard cap on the per-episode action scan (mirrors {@code SelfHealStatsService
     * #CONFOUND_AUDIT_CAP}'s reasoning): a single large bulk retry can write thousands of
     * per-item audit rows inside one episode window, and this runs per episode per cache miss.
     * When it bites, {@code truncated=true} tells the truth instead of presenting a partial
     * tally as complete — the cap drops the OLDEST rows (the query is {@code ts DESC}), so the
     * count is always at least the freshest attributable activity. The fetch itself asks for
     * {@code EPISODE_ACTION_CAP + 1} (this file's own cap+1 idiom, see {@code
     * IncidentQueryService#list}'s hard-cap comment): a full {@code cap+1} page proves the cap
     * actually bit without a separate count query, and the caller trims back to {@code cap}
     * before tallying so the truncated count itself never includes the sentinel row.
     */
    static final int EPISODE_ACTION_CAP = 5_000;

    private final AuditEntryRepository audits;
    private final ObjectMapper json;
    private final Clock clock;
    private final Cache<Long, IncidentDetail.EpisodeActionAttribution> cache;

    public EpisodeActionAttributionService(
            AuditEntryRepository audits, ObjectMapper json, Clock clock, InspectorProperties properties) {
        this.audits = audits;
        this.json = json;
        this.clock = clock;
        // TTL aligned to the sampler beat (§3.2 precedent) — short enough that a late-settling
        // audit row cannot stay unaccounted for long, simple enough to need no bespoke cache-key
        // machinery keyed on "latest relevant audit row".
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.snapshotOrDefault().bucketWidthOrDefault())
                .build();
    }

    /**
     * One attribution per episode, keyed by episode id (never positional — a caller must not
     * assume the map's size matches the input list, degrade-safe branches below can short-circuit
     * to an entry-per-episode fill or, on a corrupted engine set, to no entries at all). The
     * engine set is read from {@code incident}'s CURRENT (raw, unscoped) {@code countsByEngine} —
     * the same fleet-wide, current-snapshot-only precedent {@code
     * SelfHealStatsService#engineIdsOf} already accepts: a class's historic per-episode engine set
     * is not reconstructable from the ledger, so a registry change can shift which engines a
     * historic episode's tally draws from. A corrupted/empty engine set degrades every episode to
     * the honest empty attribution rather than failing the caller's read. Because the engine set
     * is UNSCOPED, {@code IncidentQueryService} must never call this for a partially-scoped
     * incident projection (R-SAFE-17) — see its call site's comment.
     */
    public Map<Long, IncidentDetail.EpisodeActionAttribution> forEpisodes(
            Incident incident, List<IncidentEpisode> episodesNewestFirst) {
        Set<String> engineIds = engineIdsOf(incident);
        Map<Long, IncidentDetail.EpisodeActionAttribution> out = new LinkedHashMap<>();
        if (engineIds.isEmpty()) {
            for (IncidentEpisode episode : episodesNewestFirst) {
                out.put(episode.getId(), EMPTY);
            }
            return out;
        }
        for (IncidentEpisode episode : episodesNewestFirst) {
            out.put(episode.getId(), cache.get(episode.getId(), id -> attribute(episode, engineIds, clock.instant())));
        }
        return out;
    }

    private IncidentDetail.EpisodeActionAttribution attribute(
            IncidentEpisode episode, Set<String> engineIds, Instant now) {
        Instant until = episode.getEndedAt() != null ? episode.getEndedAt() : now;
        // cap+1: a full page proves the cap bit without a separate count query (IncidentQueryService
        // #list's idiom) — trim back to the cap before tallying so `count`/`truncated` never see
        // the sentinel (cap+1)-th row.
        List<AttributedActionPoint> points = audits.findAttributableActionPoints(
                engineIds, episode.getStartedAt(), until, PageRequest.of(0, EPISODE_ACTION_CAP + 1));
        boolean truncated = points.size() > EPISODE_ACTION_CAP;
        if (truncated) {
            points = points.subList(0, EPISODE_ACTION_CAP);
        }
        if (points.isEmpty()) {
            return EMPTY;
        }
        Map<String, Long> byVerb = new LinkedHashMap<>();
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        for (AttributedActionPoint point : points) {
            byVerb.merge(point.action(), 1L, Long::sum);
            byOutcome.merge(point.outcome().name(), 1L, Long::sum);
        }
        return new IncidentDetail.EpisodeActionAttribution(points.size(), byVerb, byOutcome, truncated);
    }

    private Set<String> engineIdsOf(Incident incident) {
        try {
            Map<String, Map<String, Long>> parsed = json.readValue(incident.getCountsByEngine(), COUNTS_SHAPE);
            return parsed != null ? parsed.keySet() : Set.of();
        } catch (JsonProcessingException | RuntimeException e) {
            // Degrade-safe (informational-only surface, matching SelfHealStatsService#engineIdsOf):
            // a corrupted display blob drops attribution for this ONE incident rather than 500ing
            // the detail read it decorates.
            log.warn(
                    "episode action attribution: counts_by_engine unreadable for incident {} — {}",
                    incident.getId(),
                    e.toString());
            return Set.of();
        }
    }
}
