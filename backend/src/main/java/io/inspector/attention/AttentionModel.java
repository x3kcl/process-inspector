package io.inspector.attention;

import java.util.Map;

/**
 * One snapshot of every error class's ledger evidence plus the fleet baseline the M factor
 * normalizes against (ALARM-COST-MODEL.md §6). Built in THREE bounded DB aggregates against the
 * BFF's own Postgres — never per card, never an engine call — and cached whole for
 * {@code inspector.triage.attention.model-ttl}.
 *
 * @param byKey {@code signatureHash#algoVersion} → that class's evidence
 * @param fleetMedianMttrSeconds median closed-episode duration across the WHOLE ledger, or
 *     {@code null} when nothing has ever closed (the measured state of the pilot today) — the M
 *     factor then reads neutral for every class, which is precisely why the ordering currently
 *     degrades to count-only
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
