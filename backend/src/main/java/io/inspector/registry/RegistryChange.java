package io.inspector.registry;

/**
 * A single intended dangerous registry write (docs/REGISTRY-CRUD.md §9, R-SAFE-08). Unlike the IdP
 * {@code GrantChange} this needs no serialized payload — the operation is fully described by
 * {@code kind} + {@code engineId}; {@code ENABLE_READ_WRITE} always means the read-write flip
 * (read-only never needs four-eyes). Serialized nowhere; the proposal store keeps kind/engineId as
 * plain columns.
 */
public record RegistryChange(Kind kind, String engineId) {

    public enum Kind {
        ENABLE_READ_WRITE,
        REMOVE,
        PURGE
    }

    public static RegistryChange enableReadWrite(String engineId) {
        return new RegistryChange(Kind.ENABLE_READ_WRITE, engineId);
    }

    public static RegistryChange remove(String engineId) {
        return new RegistryChange(Kind.REMOVE, engineId);
    }

    public static RegistryChange purge(String engineId) {
        return new RegistryChange(Kind.PURGE, engineId);
    }

    /** Human-legible one-liner for the audit summary + the R-SAFE-08 proposal inbox. */
    public String summary() {
        return switch (kind) {
            case ENABLE_READ_WRITE -> "enable read-write on engine '" + engineId + "'";
            case REMOVE -> "remove engine '" + engineId + "'";
            case PURGE -> "purge engine '" + engineId + "'";
        };
    }
}
