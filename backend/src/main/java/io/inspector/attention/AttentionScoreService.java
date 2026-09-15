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
 * per error class, {@code A(c) = F·R·S} over the incident ledger's own history, joined at RENDER
 * time exactly where ack state joins today. (It was {@code F·R·M·S} until #399/§17 neutralized
 * {@code M}: {@code medMTTR} is measured from first sighting to the operator's resolve click, so
 * it contains the queue wait this ordering controls and is endogenous to its own output. The
 * estimator and its clamp knobs are retained and still reported on {@code factors.mttr}; only the
 * score stopped consuming it.)
 *
 * <p><b>Zero new engine calls</b> (§4.1/§9). Every input is already in the BFF's own Postgres: the
 * F factor is a DB-side positive-delta aggregate over {@code incident_occurrence}, the M
 * diagnostic reads closed {@code incident_episode} rows, R reads {@code incident.last_seen}, and
 * S is CONSUMED from track
 * R2's statistic (#351) rather than recomputed here. The Stage 0 iron rule (count-only/{@code
 * size=1} aggregation queries plus the dedicated DLQ scan, never the grid-search plan) is
 * untouched — this service consumes the aggregation's output and never adds a leg to it.
 *
 * <p><b>Flag-off by default, and provably inert when off.</b> The R1 data-maturity gate is
 * measured NOT MET (§7, 0 of 5 axes). G5 counts TRUSTED ledger span, not recorded span — the
 * pilot's history was 99 % blind ({@code cycle_complete = false}) until 2026-08-04T15:39Z (the
 * instant an unreachable engine left the aggregation scope, NOT the instant it became reachable —
 * §14.2 correction), and a fit-plus-holdout over discarded deltas is a fit
 * over nothing — so earliest satisfaction was ≈ 2026-09-29, not the ≈ 2026-09-14 stated here before
 * the #365 amendment round re-measured it (§7 correction). <b>Amended again by #372 (§16.7):</b>
 * G5 now counts the trusted span of the CURRENT ERA — one unchanging observation scope
 * ({@code incident_occurrence.fleet}, V22) — because a fit must difference within one fleet. V22
 * records scope from its deploy forward and deliberately does NOT backfill a guessed fleet onto
 * existing rows (scope at write time cannot be reconstructed; asserting it would be fabrication),
 * so every pre-V22 row is {@code fleet = ''} = comparable to nothing and the era clock STARTS AT
 * V22 DEPLOY: measured G5 resets to 0 d, earliest satisfaction ≈ V22 deploy + 56 d. The exact
 * post-deploy era-start instant is owed as a §5-method measurement once the migration has run —
 * it cannot be extracted beforehand, and this comment records the rule rather than a made-up date. The score is measured IDENTICAL to
 * count-only ordering across all 21,229 recorded pilot buckets (§5.5, Kendall tau = 1.0 — for most
 * of them via the F2 neutrality rule, the whole fleet tied at an unknown-arrivals F of 1). With
 * {@code inspector.triage.attention-ordering} false — the shipped default
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
        warnIfTheBurstWindowIsShorterThanTheModelDwell(attention);
    }

    /**
     * §4.1a's dwell argument assumes {@code W > model-ttl}: the burst bins are evaluated when the
     * model is BUILT, so a flood detected at build time both surfaces within one TTL and survives
     * at least one rebuild. A shorter window is legal (an operator may want a tighter flood
     * definition) but silently loses that property — a flood can then open and close entirely
     * inside one cached model and never be seen. Warn, never refuse: unlike an inverted Schmitt
     * trigger this is a trade-off, not a contradiction.
     */
    private void warnIfTheBurstWindowIsShorterThanTheModelDwell(InspectorProperties.Attention attention) {
        Duration burstWindow = attention.burstWindowOrDefault();
        Duration modelTtl = attention.modelTtlOrDefault();
        if (burstWindow.compareTo(modelTtl) <= 0) {
            log.warn(
                    "attention: burst-window {} <= model-ttl {} — a flood may open and close inside one"
                            + " cached model and never surface (ALARM-COST-MODEL §4.1a expects W > TTL)",
                    burstWindow,
                    modelTtl);
        }
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
                scored.add(group.withAttention(score(group, model, now, response.perEngine())));
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
     *
     * <p><b>#388 known limitation.</b> This entry point has no {@code ErrorGroup}/{@code
     * perEngine} to derive the dead-letter split from, so it ALWAYS composes with
     * {@link AttentionRationale.DeadLetterEvidence#ABSENT} — the {@code SELF_HEAL_LIKELY}
     * rationale rendered on {@code /incidents} (via {@code IncidentQueryService.attentionScore})
     * never carries the population-aware suffix, even when the Stage 0 dashboard's rationale for
     * the SAME class does (see {@link #score(ErrorGroup, AttentionModel, Instant, Map)}).
     * Recorded, not silently accepted: RETRYING-RISK-LANE.md §4.2 names this alongside the
     * client-composed {@code SelfHealBadge}'s own divergence (which gets a static teaching
     * sentence instead). Resolution path: plumb the split into {@code IncidentSummary} only if
     * demand shows it is worth the DTO surface.
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

    /**
     * The Stage 0 dashboard path — the ONLY caller that can ever supply real #388 evidence
     * ({@code forClass()} always passes {@link AttentionRationale.DeadLetterEvidence#ABSENT},
     * by construction, since it has no {@code ErrorGroup}/{@code perEngine} to derive it from).
     */
    private AttentionScore score(
            ErrorGroup group,
            AttentionModel model,
            Instant now,
            Map<String, TriageDashboardResponse.PerEngineTriage> perEngine) {
        return AttentionScoreCalculator.score(
                group.total(),
                model.historyOf(group.signatureHash(), group.algoVersion()),
                model.fleetMedianMttrSeconds(),
                selfHealStats(group),
                config,
                now,
                AttentionRationale.DeadLetterEvidence.of(group, perEngine));
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
        Instant since = now.minus(Duration.ofDays(config.arrivalsWindowDays()));
        Map<Long, String> keyByIncidentId = new LinkedHashMap<>();
        Map<String, Instant> lastSeenByKey = new LinkedHashMap<>();
        // Window-scoped, never findAll(): `incident` rows are never deleted, so an unscoped
        // fetch on this 5-minute rebuild grows without bound. A class last seen before the F
        // window contributes nothing to it anyway (no in-window occurrence rows) and reads
        // ClassHistory.none() — neutral, exactly as §4.1's degradation rule prescribes.
        for (Incident row : incidents.findByLastSeenGreaterThanEqual(since)) {
            String key = AttentionModel.key(row.getSignatureHash(), row.getAlgoVersion());
            keyByIncidentId.put(row.getId(), key);
            lastSeenByKey.put(key, row.getLastSeen());
        }
        if (keyByIncidentId.isEmpty()) {
            return AttentionModel.empty();
        }

        // {incidentId, arrivals, observedSamples, trustedSamples, burstArrivals,
        //  priorBurstArrivals, burstObservedSamples, burstTrustedSamples} — ONE pass. The sample
        // counts are what make the difference between "0 arrivals" and "we were not allowed to
        // trust a single sample"; the burst bins are a FILTERED subset of the same deltas (§4.1a),
        // anchored on the model-build instant, never a second query.
        Instant burstSince = now.minus(config.burstWindow());
        Instant priorBurstSince = now.minus(config.burstWindow().multipliedBy(2));
        Map<Long, ArrivalEvidence> arrivalsById = new HashMap<>();
        for (Object[] arrival : occurrences.arrivalsSince(since, burstSince, priorBurstSince)) {
            Long incidentId = asLong(arrival[0]);
            Long arrivals = asLong(arrival[1]);
            Long observed = asLong(arrival[2]);
            Long trusted = asLong(arrival[3]);
            if (incidentId != null && arrivals != null && observed != null && trusted != null) {
                arrivalsById.put(
                        incidentId,
                        new ArrivalEvidence(
                                arrivals,
                                observed,
                                trusted,
                                orZero(asLong(arrival[4])),
                                orZero(asLong(arrival[5])),
                                orZero(asLong(arrival[6])),
                                orZero(asLong(arrival[7]))));
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
        keyByIncidentId.forEach((incidentId, key) -> {
            ArrivalEvidence evidence = arrivalsById.getOrDefault(incidentId, ArrivalEvidence.NONE);
            byKey.put(
                    key,
                    new ClassHistory(
                            lastSeenByKey.get(key),
                            evidence.arrivals(),
                            evidence.unknown(),
                            evidence.discarded(),
                            evidence.burstArrivals(),
                            evidence.priorBurstArrivals(),
                            evidence.burstUnknown(),
                            evidence.discardedBurst(),
                            closedById.getOrDefault(incidentId, List.of())));
        });
        return new AttentionModel(byKey, Quantiles.median(fleetClosed));
    }

    /**
     * One incident's row from {@code arrivalsSince}. {@link #unknown()} is the honesty rail the
     * review added: a window that HAD differenceable samples but could trust NONE of them (a
     * permanently scan-capped engine, or a whole-window outage) reports arrival volume as
     * UNKNOWN, so {@code F} degrades to the multiplicative identity instead of zeroing the score.
     * An incident absent from the aggregate had no in-window sample at all — a genuine zero, and
     * the fleet-uniform "no history" degradation the design already guarantees.
     */
    private record ArrivalEvidence(
            long arrivals,
            long observedSamples,
            long trustedSamples,
            long burstArrivals,
            long priorBurstArrivals,
            long burstObservedSamples,
            long burstTrustedSamples) {

        private static final ArrivalEvidence NONE = new ArrivalEvidence(0L, 0L, 0L, 0L, 0L, 0L, 0L);

        boolean unknown() {
            return observedSamples > 0 && trustedSamples == 0;
        }

        long discarded() {
            return Math.max(0L, observedSamples - trustedSamples);
        }

        /**
         * The burst bin's own honesty rail (§4.1a), the same shape one window down: samples, but
         * not one we were allowed to trust. It forces the flood gate OFF — an unknown rate can
         * suppress a promotion, never justify one, and never a demotion.
         */
        boolean burstUnknown() {
            return burstObservedSamples > 0 && burstTrustedSamples == 0;
        }

        long discardedBurst() {
            return Math.max(0L, burstObservedSamples - burstTrustedSamples);
        }
    }

    /** Native aggregates hand back {@code BigInteger}/{@code BigDecimal}/{@code Long} by driver. */
    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** A burst column a driver handed back as something un-numeric reads 0 — never a fake flood. */
    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }
}
