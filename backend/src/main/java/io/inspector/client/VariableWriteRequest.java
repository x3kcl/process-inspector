package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of a variable PUT (#720): process-instance scope, execution-local scope and CMMN case
 * scope. {@code value} is ALWAYS sent — null is a legal variable value, so {@code "value":null}
 * is a write, never an omission — and stays an {@code Object}: it leaves as the JSON type it came
 * in as. A null {@code type} (the operator chose none) and a null {@code scope} are OMITTED.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VariableWriteRequest(
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String type,
        Object value,
        @JsonInclude(JsonInclude.Include.NON_NULL) String scope) {

    /** Process-instance or case scope. */
    public static VariableWriteRequest of(String name, String type, Object value) {
        return new VariableWriteRequest(name, type, value, null);
    }

    /**
     * Execution-local (step-local): {@code scope:"local"} keeps the engine from promoting the
     * variable to process scope (SPEC §4a; flowable-rest §2).
     */
    public static VariableWriteRequest local(String name, String type, Object value) {
        return new VariableWriteRequest(name, type, value, "local");
    }
}
