package io.inspector.attention;

import java.util.Map;

/**
 * One snapshot of every error class's ledger evidence plus the fleet baseline the (retained but
 * no longer score-consumed, #399/§17) M estimator normalizes against (ALARM-COST-MODEL.md §6). Built in THREE bounded DB aggregates against the
 * BFF's own Postgres — never per card, never an engine call — and cached whole for
 * {@code inspector.triage.attention.model-ttl}.
 *
 * @param byKey {@code signatureHash#algoVersion} → that class's evidence
 * @param fleetMedianMttrSeconds median closed-episode duration across the WHOLE ledger, or
 *     {@code null} when nothing has ever closed (the measured state of the pilot today) — the M
 *     estimator then reads neutral for every class. Since #399 that no longer changes any
 *     ordering either way: M is identically 1 in the v1 score, and the count-only degradation is
 *     carried by F and S alone
 */
public record AttentionModel(Map<String, ClassHistory> byKey, Long fleetMedianMttrSeconds) {

    public AttentionModel {
        byKey = byKey == null ? Map.of() : Map.copyOf(byKey);
    }

    public static AttentionModel empty() {
        return new AttentionModel(Map.of(), null);
    }

    /** Never null: an unknown class reads {@link ClassHistory#none()} — neutral, not absent. */
    public ClassHistory historyOf(String signatureHash, int algoVersion) {
        return byKey.getOrDefault(key(signatureHash, algoVersion), ClassHistory.none());
    }

    public static String key(String signatureHash, int algoVersion) {
        return signatureHash + '#' + algoVersion;
    }
}
