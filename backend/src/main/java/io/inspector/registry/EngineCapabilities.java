package io.inspector.registry;

/**
 * What a registered engine's REST API supports (ARCHITECTURE §2.5). The UI greys gated
 * tools out per engine with a reason; the BFF rejects gated calls — never a confusing
 * 404 from the engine.
 *
 * Four flags fall out of version cliffs; {@code activityHistory} is probed because the
 * engine's history level is configuration, not version.
 */
public record EngineCapabilities(
        boolean changeState,         // POST .../change-state — Flowable ≥ 6.4
        boolean migration,           // process-migration API — Flowable ≥ 6.5
        boolean externalWorkerJobs,  // external-worker job queue — Flowable ≥ 6.8
        boolean scopeType,           // scopeType field on job rows — Flowable ≥ 6.8
        boolean activityHistory      // history level records activities (probed, not derived)
) {
    public static EngineCapabilities none() {
        return new EngineCapabilities(false, false, false, false, false);
    }

    /**
     * Derives the version-cliff flags. Unparseable/unknown versions yield no capabilities —
     * gating errs conservative rather than sending a call the engine cannot answer.
     */
    public static EngineCapabilities fromVersion(String version, boolean activityHistoryProbe) {
        int[] mm = parseMajorMinor(version);
        if (mm == null) {
            return new EngineCapabilities(false, false, false, false, activityHistoryProbe);
        }
        int major = mm[0], minor = mm[1];
        boolean v64 = major > 6 || (major == 6 && minor >= 4);
        boolean v65 = major > 6 || (major == 6 && minor >= 5);
        boolean v68 = major > 6 || (major == 6 && minor >= 8);
        return new EngineCapabilities(v64, v65, v68, v68, activityHistoryProbe);
    }

    /** "6.8.0.1" → {6,8}; null when the version is missing or not dotted-numeric. */
    static int[] parseMajorMinor(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new int[] {major, minor};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
