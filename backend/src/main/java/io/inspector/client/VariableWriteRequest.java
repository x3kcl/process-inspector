package io.inspector.client;

/** The body of a variable PUT (#720). Stub: the typed record lands with the refactor. */
public record VariableWriteRequest(String name, String type, Object value, String scope) {

    public static VariableWriteRequest of(String name, String type, Object value) {
        return new VariableWriteRequest(null, null, null, null);
    }

    public static VariableWriteRequest local(String name, String type, Object value) {
        return new VariableWriteRequest(null, null, null, null);
    }
}
