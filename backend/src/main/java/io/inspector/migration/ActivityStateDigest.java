package io.inspector.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * A stable fingerprint of an instance's token position: the SHA-256 of the sorted multiset of
 * {@code (activityId, executionCount)} across the instance's active executions (P0 re-lock
 * decision P0-4). It replaces the impossible {@code validationDigest} in the §5 compare-and-set:
 * the preview returns the digest it computed, and execute recomputes it server-fresh and asserts
 * equality — if a parallel actor advanced the instance or moved its token between preview and
 * execute, the digests diverge and execute refuses ("instance moved since you previewed").
 *
 * <p>The multiset (with per-activity execution counts), not a plain id set, so token
 * multiplicity is also under the assertion — a parallel gateway that spawned or joined a token
 * without changing the activity-id set still changes the digest.
 */
public final class ActivityStateDigest {

    private ActivityStateDigest() {}

    /**
     * Compute the digest from the active activity ids (WITH repeats — one entry per active
     * execution at that activity). Order-independent: the ids are grouped and counted, then the
     * canonical {@code id:count|id:count} form is hashed.
     */
    public static String of(Collection<String> activeActivityIds) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String id : activeActivityIds) {
            if (id != null) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (canonical.length() > 0) {
                canonical.append('|');
            }
            canonical.append(e.getKey()).append(':').append(e.getValue());
        }
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }
}
