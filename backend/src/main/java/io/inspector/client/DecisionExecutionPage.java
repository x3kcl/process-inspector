package io.inspector.client;

/** One page of historic DMN decision executions. RED stub (#474): no components yet. */
public record DecisionExecutionPage() {
    public long total() {
        throw new UnsupportedOperationException("#474 RED stub");
    }

    public java.util.List<DecisionExecutionRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("#474 RED stub");
    }
}
