package io.inspector.attention;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.inspector.attention.CounterfactualAckReplay.SeriesPoint;
import io.inspector.config.InspectorProperties;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentOccurrence;
import io.inspector.incident.IncidentOccurrenceRepository;
import io.inspector.incident.IncidentRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * C3 — the R-BAU-01 auto-resurface threshold, model-derived per error class
 * (ALARM-COST-MODEL.md §3.3, #353), with today's hand-tuned constant as the fallback.
 *
 * <p><b>Default OFF, and that is the design's instruction, not caution.</b> §3.3 is explicit:
 * "C3 switches to the derived value only after the full §7 gate, and until then the constant 20 %
 * stays and the config key keeps working as today". The §7 gate is measured NOT MET (0 of 5),
 * and G4 in particular — ≥ 10 completed ack lifecycles — stands at ZERO acks ever recorded. So
 * {@code inspector.triage.attention.derived-resurface-threshold} defaults false and this service
 * answers {@code inspector.triage.ack-resurface-threshold-pct} (20) verbatim: no silent behavior
 * change, and the per-deployment override keeps working exactly as before.
 *
 * <p>When an operator does opt in, the per-class estimate is fit by counterfactual-ack replay
 * ({@link CounterfactualAckReplay}) over that class's own occurrence series, cached for
 * {@code inspector.triage.attention.model-ttl}. Any failure, any class with too little series to
 * segment, any class whose replay never fired a resurface (an unfittable, evidence-free class),
 * and any class with no ledger row at all falls back to the constant — an ack policy must never
 * become less predictable because an estimator had a bad day. And a value that WAS fit is floored
 * at the constant, so opting in can only ever raise the jitter guard, never quietly halve it (see
 * {@link #fit}).
 */
@Service
public class ResurfaceThresholdEstimator {

    private static final Logger log = LoggerFactory.getLogger(ResurfaceThresholdEstimator.class);

    private final IncidentRepository incidents;
    private final IncidentOccurrenceRepository occurrences;
    private final Clock clock;
    private final boolean derived;
    private final int constantPct;
    private final int floorPct;
    private final double budgetPer30AckDays;
    private final Duration seriesWindow;
    private final Duration bucketWidth;
    private final Cache<String, Integer> cache;

    public ResurfaceThresholdEstimator(
            IncidentRepository incidents,
            IncidentOccurrenceRepository occurrences,
            Clock clock,
            InspectorProperties properties) {
        this.incidents = incidents;
        this.occurrences = occurrences;
        this.clock = clock;
        InspectorProperties.Attention attention = properties.triageOrDefault().attentionOrDefault();
        this.derived = attention.derivedResurfaceThresholdOrDefault();
        this.constantPct = properties.triageOrDefault().ackResurfaceThresholdPctOrDefault();
        this.floorPct = attention.resurfaceFloorPctOrDefault();
        this.budgetPer30AckDays = attention.resurfaceFalseBudgetPer30AckDaysOrDefault();
        this.seriesWindow = Duration.ofDays(attention.arrivalsWindowDaysOrDefault());
        this.bucketWidth = properties.snapshotOrDefault().bucketWidthOrDefault();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(attention.modelTtlOrDefault())
                .build();
    }

    /** Today's constant unless an operator opted into the derived value AND it could be fit. */
    public int thresholdPct(String signatureHash, int algoVersion) {
        if (!derived) {
            return constantPct;
        }
        String key = AttentionModel.key(signatureHash, algoVersion);
        try {
            return cache.get(key, ignored -> fit(signatureHash, algoVersion));
        } catch (RuntimeException e) {
            log.warn(
                    "resurface threshold: falling back to the {}% constant for {} — {}",
                    constantPct, key, e.toString());
            return constantPct;
        }
    }

    /**
     * <b>Opting in can only ever be MORE conservative</b> (review fix, ALARM-COST-MODEL §3.3
     * correction). Two rails, both added because the shipped code did the opposite of what the
     * design sells on the data the design was built for:
     *
     * <ol>
     *   <li>An UNFITTABLE class keeps the constant — {@link CounterfactualAckReplay#thresholdPct}
     *       is empty when no segment could be formed OR when the replay never fired a single
     *       resurface (a fit satisfied by having no data; the measured pilot state, §5.6).
     *   <li>A fitted value is FLOORED at {@code inspector.triage.ack-resurface-threshold-pct}.
     *       §3.3's purpose is to lift the threshold clear of normal jitter so a resurface fires on
     *       genuine growth; a derived value BELOW today's constant would instead interrupt the
     *       operator more often than the constant does, which is the opposite of the guard the
     *       doc promises. The derived value therefore only ever moves the threshold UP.
     * </ol>
     */
    private int fit(String signatureHash, int algoVersion) {
        Optional<Incident> row = incidents.findBySignatureHashAndAlgoVersion(signatureHash, algoVersion);
        if (row.isEmpty()) {
            return constantPct; // no history at all ⇒ no derivation is honest
        }
        List<IncidentOccurrence> points =
                occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(
                        row.get().getId(), clock.instant().minus(seriesWindow));
        List<SeriesPoint> series = new ArrayList<>(points.size());
        for (IncidentOccurrence point : points) {
            series.add(new SeriesPoint(point.getTotal(), point.isTruncated()));
        }
        OptionalInt derivedPct =
                CounterfactualAckReplay.thresholdPct(series, bucketWidth, floorPct, budgetPer30AckDays);
        if (derivedPct.isEmpty()) {
            return constantPct;
        }
        return Math.max(constantPct, derivedPct.getAsInt());
    }
}
