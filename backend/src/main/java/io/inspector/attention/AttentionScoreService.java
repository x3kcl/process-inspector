package io.inspector.attention;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.AttentionScore;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.SelfHealStats;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentEpisodeRepository;
import io.inspector.incident.IncidentOccurrenceRepository;
import io.inspector.incident.IncidentRepository;
import io.inspector.selfheal.SelfHealStatsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The cost-aware attention score (ALARM-COST-MODEL.md §4, #353, gated on the locked design #348):
 * per error class, {@code A(c) = F·R·M·S} over the incident ledger's own history, joined at RENDER
 * time exactly where ack state joins today.
 *
 * <p><b>Zero new engine calls</b> (§4.1/§9). Every input is already in the BFF's own Postgres: the
 * F factor is a DB-side positive-delta aggregate over {@code incident_occurrence}, M reads closed
 * {@code incident_episode} rows, R reads {@code incident.last_seen}, and S is CONSUMED from track
 * R2's statistic (#351) rather than recomputed here. The Stage 0 iron rule (count-only/{@code
 * size=1} aggregation queries plus the dedicated DLQ scan, never the grid-search plan) is
 * untouched — this service consumes the aggregation's output and never adds a leg to it.
 *
 * <p><b>Flag-off by default, and provably inert when off.</b> The R1 data-maturity gate is
 * measured NOT MET (§7, 0 of 5 axes; earliest G5 satisfaction ≈ 2026-09-14) and the score is
 * measured IDENTICAL to count-only ordering across all 21,229 recorded pilot buckets (§5.5,
 * Kendall tau = 1.0). With {@code inspector.triage.attention-ordering} false — the shipped default
 * — {@link #decorate} returns its argument UNCHANGED (the same instance): no query runs, no
 * {@code attention} block is serialized, and the card order is byte-for-byte today's. {@code
 * AttentionOrderingNeutralityTest} is the proof, and it also proves the stronger property that
 * even with the flag ON and an empty ledger the ordering is unchanged.
 *
 * <p><b>Ordering only, never hides</b> (§4.1, R-BAU-01 verbatim): the decoration is a permutation
 * plus a read-only overlay. No card is filtered, no section membership changes, acknowledged
 * groups keep their labeled collapse and their three resurface triggers. Status derivation
 * (ARCHITECTURE §2.3) is not touched at all.
 *
 * <p><b>Degrade-safe.</b> An ordering hint must never break the landing it decorates: any failure
 * (store down, corrupt row, a poisoned class) warns once and serves the undecorated response —
 * the same contract {@code SelfHealStatsService} carries for its own informational surface.
 */
@Service
public class AttentionScoreService {

    private static final Logger log = LoggerFactory.getLogger(AttentionScoreService.class);
    private static final String MODEL_KEY = "model";

    private final IncidentRepository incidents;
    private final IncidentEpisodeRepository episodes;
    private final IncidentOccurrenceRepository occurrences;
    private final SelfHealStatsService selfHeal;
    private final Clock clock;
    private final boolean enabled;
    private final AttentionConfig config;
    private final Cache<String, AttentionModel> modelCache;

    public AttentionScoreService(
            IncidentRepository incidents,
            IncidentEpisodeRepository episodes,
            IncidentOccurrenceRepository occurrences,
            SelfHealStatsService selfHeal,
            Clock clock,
            InspectorProperties properties) {
        this.incidents = incidents;
        this.episodes = episodes;
        this.occurrences = occurrences;
        this.selfHeal = selfHeal;
        this.clock = clock;
        this.enabled = properties.triageOrDefault().attentionOrderingOrDefault();
        InspectorProperties.Attention attention = properties.triageOrDefault().attentionOrDefault();
        this.config = AttentionConfig.from(attention);
        this.modelCache = Caffeine.newBuilder()
                .expireAfterWrite(attention.modelTtlOrDefault())
                .build();
    }

    /** True only when an operator has explicitly opted in (§7 — the gate is not met). */
    public boolean enabled() {
        return enabled;
    }

    /**
     * The render-time join: score every card, then re-order. Flag off ⇒ the ARGUMENT is returned
     * unchanged, which is the neutrality guarantee in its strongest form.
     */
    public TriageDashboardResponse decorate(TriageDashboardResponse response) {
        if (!enabled
                || response == null
                || response.errorGroups() == null
                || response.errorGroups().isEmpty()) {
            return response;
        }
        try {
            AttentionModel model = model();
            Instant now = clock.instant();
            List<ErrorGroup> scored = new ArrayList<>(response.errorGroups().size());
            for (ErrorGroup group : response.errorGroups()) {
                scored.add(group.withAttention(score(group, model, now)));
            }
            return new TriageDashboardResponse(
                    response.asOf(),
                    response.engines(),
                    response.statusCounts(),
                    response.statusCountsByEngine(),
                    AttentionOrdering.order(scored),
                    response.perEngine());
        } catch (RuntimeException e) {
            // An ordering hint never breaks the landing: serve today's ordering, undecorated.
            log.warn("attention score: dashboard decoration skipped — {}", e.toString());
            return response;
        }
    }

    /**
     * One class's score for the incident ledger's list/detail. The ledger keeps its own SERVER
     * ordering ({@code lastSeen DESC} — the #308 hard cap must drop the OLDEST rows) and its
     * client-derived sections; this only supplies the score those sections order WITHIN.
     * {@code null} when the flag is off or anything at all goes wrong.
     */
    public AttentionScore forClass(String signatureHash, int algoVersion, long liveTotal, SelfHealStats stats) {
        if (!enabled) {
            return null;
        }
        try {
            AttentionModel model = model();
            return AttentionScoreCalculator.score(
                    liveTotal,
                    model.historyOf(signatureHash, algoVersion),
                    model.fleetMedianMttrSeconds(),
                    stats,
                    config,
                    clock.instant());
        } catch (RuntimeException e) {
            log.warn("attention score: class {} skipped — {}", signatureHash, e.toString());
            return null;
        }
    }

    private AttentionScore score(ErrorGroup group, AttentionModel model, Instant now) {
        return AttentionScoreCalculator.score(
                group.total(),
                model.historyOf(group.signatureHash(), group.algoVersion()),
                model.fleetMedianMttrSeconds(),
                selfHealStats(group),
                config,
                now);
    }

    /** The R2 statistic is a DEPENDENCY, never a duplication (§4.2) — and never a hard one. */
    private SelfHealStats selfHealStats(ErrorGroup group) {
        try {
            return selfHeal.get(group.signatureHash(), group.algoVersion());
        } catch (RuntimeException e) {
            return null; // ⇒ S = 1 (neutral): a missing statistic must not demote anything
        }
    }

    /* ---------------- the model: three bounded aggregates, cached whole ---------------- */

    AttentionModel model() {
        return modelCache.get(MODEL_KEY, k -> build(clock.instant()));
    }

    private AttentionModel build(Instant now) {
        Map<Long, String> keyByIncidentId = new LinkedHashMap<>();
        Map<String, Instant> lastSeenByKey = new LinkedHashMap<>();
        for (Incident row : incidents.findAll()) {
            String key = AttentionModel.key(row.getSignatureHash(), row.getAlgoVersion());
            keyByIncidentId.put(row.getId(), key);
            lastSeenByKey.put(key, row.getLastSeen());
        }
        if (keyByIncidentId.isEmpty()) {
            return AttentionModel.empty();
        }

        Instant since = now.minus(Duration.ofDays(config.arrivalsWindowDays()));
        Map<Long, Long> arrivalsById = new HashMap<>();
        for (Object[] arrival : occurrences.arrivalsSince(since)) {
            Long incidentId = asLong(arrival[0]);
            Long arrivals = asLong(arrival[1]);
            if (incidentId != null && arrivals != null) {
                arrivalsById.put(incidentId, arrivals);
            }
        }

        Map<Long, List<Long>> closedById = new HashMap<>();
        List<Long> fleetClosed = new ArrayList<>();
        for (Object[] episode : episodes.closedEpisodeDurationSeconds()) {
            Long incidentId = asLong(episode[0]);
            Long seconds = asLong(episode[1]);
            if (incidentId == null || seconds == null || seconds < 0) {
                continue;
            }
            closedById.computeIfAbsent(incidentId, id -> new ArrayList<>()).add(seconds);
            fleetClosed.add(seconds);
        }

        Map<String, ClassHistory> byKey = new LinkedHashMap<>();
        keyByIncidentId.forEach((incidentId, key) -> byKey.put(
                key,
                new ClassHistory(
                        lastSeenByKey.get(key),
                        arrivalsById.getOrDefault(incidentId, 0L),
                        closedById.getOrDefault(incidentId, List.of()))));
        return new AttentionModel(byKey, Quantiles.median(fleetClosed));
    }

    /** Native aggregates hand back {@code BigInteger}/{@code BigDecimal}/{@code Long} by driver. */
    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
