package io.inspector.attention;

import io.inspector.config.InspectorProperties;
import java.time.Duration;

/**
 * The attention model's knobs, resolved once and passed to the PURE scoring math
 * (ALARM-COST-MODEL.md §4.1) so {@link AttentionScoreCalculator} needs no Spring types and is
 * exhaustively testable against synthetic inputs — the rung-1 doctrine.
 */
public record AttentionConfig(
        Duration recencyHalfLife,
        int arrivalsWindowDays,
        int minClosedEpisodes,
        double mttrClampLow,
        double mttrClampHigh,
        double selfHealFloor,
        Duration selfHealHorizon,
        Duration burstWindow,
        int burstOnset,
        int burstExit,
        double burstWeight) {

    public static AttentionConfig from(InspectorProperties.Attention props) {
        return new AttentionConfig(
                props.recencyHalfLifeOrDefault(),
                props.arrivalsWindowDaysOrDefault(),
                props.minClosedEpisodesOrDefault(),
                props.mttrClampLowOrDefault(),
                props.mttrClampHighOrDefault(),
                props.selfHealFloorOrDefault(),
                props.selfHealHorizonOrDefault(),
                props.burstWindowOrDefault(),
                props.burstOnsetOrDefault(),
                props.burstExitOrDefault(),
                props.burstWeightOrDefault());
    }

    /** The design's selected defaults, for tests and any caller without bound properties. */
    public static AttentionConfig defaults() {
        return from(new InspectorProperties.Attention(null, null, null, null, null, null, null, null, null, null));
    }
}
