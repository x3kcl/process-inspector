package io.inspector.client;

/** One historic DMN decision execution. RED stub (#474): no components yet. */
public record DecisionExecutionRecord() {
    public String id() {
        throw new UnsupportedOperationException("#474 RED stub");
    }

    public String decisionKey() {
        throw new UnsupportedOperationException("#474 RED stub");
    }

    public String decisionDefinitionKey() {
        throw new UnsupportedOperationException("#474 RED stub");
    }

    public String activityId() {
        throw new UnsupportedOperationException("#474 RED stub");
    }

    public Boolean failed() {
        throw new UnsupportedOperationException("#474 RED stub");
    }

    public String startTime() {
        throw new UnsupportedOperationException("#474 RED stub");
    }

    public String endTime() {
        throw new UnsupportedOperationException("#474 RED stub");
    }
}
