package io.inspector.registry;

/**
 * The four-eyes trigger for registry writes (docs/REGISTRY-CRUD.md §9, R-SAFE-08, #91), computed on
 * the intended change — mirrors {@code io.inspector.security.mapping.FourEyesPolicy}'s pure-static
 * shape. A second independent {@code REGISTRY_ADMIN} is MANDATORY for:
 *
 * <ul>
 *   <li>a prod enable-read-write flip (a read-only prod enable, or ANY dev/test enable, is
 *       single-actor — matches the existing typed-token scope exactly);</li>
 *   <li>ANY remove (tombstone), regardless of environment;</li>
 *   <li>ANY purge (hard-delete), regardless of environment.</li>
 * </ul>
 *
 * Pure so the escalation matrix is a rung-1 CI gate; a widen that slips through single-actor would
 * be a quiet self-approval = Sev1.
 */
public final class RegistryFourEyesPolicy {

    private RegistryFourEyesPolicy() {}

    public static boolean requiresFourEyes(RegistryChange.Kind kind, String environment) {
        return switch (kind) {
            case ENABLE_READ_WRITE -> "prod".equals(environment);
            case REMOVE, PURGE -> true;
        };
    }
}
