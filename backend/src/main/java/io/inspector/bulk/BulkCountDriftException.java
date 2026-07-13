package io.inspector.bulk;

/**
 * Tier-4 destructive-bulk wizard (SPEC §6/§7, issue #100): the operator typed a count against
 * the preview's resolved scope, but the FRESH re-resolution at submit no longer matches — the
 * set drifted (instances completed, drained from the filter, or newly matched) between preview
 * and submit. Refused pre-flight — nothing dispatched; the response carries both numbers so the
 * wizard can show the drift and ask the operator to review before retyping.
 */
public class BulkCountDriftException extends RuntimeException {

    private final int confirmedCount;
    private final int actualCount;

    public BulkCountDriftException(int confirmedCount, int actualCount) {
        super("The scope now resolves to " + actualCount + " instance" + (actualCount == 1 ? "" : "s")
                + " (you confirmed " + confirmedCount + ") — review the fresh scope and retype the count.");
        this.confirmedCount = confirmedCount;
        this.actualCount = actualCount;
    }

    public int confirmedCount() {
        return confirmedCount;
    }

    public int actualCount() {
        return actualCount;
    }
}
