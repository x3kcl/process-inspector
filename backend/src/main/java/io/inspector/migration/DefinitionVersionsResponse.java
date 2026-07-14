package io.inspector.migration;

import java.util.List;

/**
 * The definition-versions on-ramp (P0 re-lock decision: cohort visibility ships in slice-1). For
 * ONE process definition key on ONE engine, every deployed version with its count of RUNNING
 * instances — "37 running on v3 · latest v5". This is what turns single-instance migration from a
 * demo into a tool: it answers "how bad is this bad-deploy, how many are wedged, on which version"
 * before the operator migrates anything.
 *
 * <p>Read-only, count-only (Stage-0 discipline: {@code size=1} runtime queries, never a row fetch).
 *
 * @param latestVersion the highest deployed version number (the default migration target)
 * @param totalVersions the full count of deployed versions for the key
 * @param complete false when the key has more versions than the on-ramp counts (only the newest
 *     {@code versions} are returned, with counts) — the operator sees the recent cohort, never a
 *     silently-truncated "all versions"
 * @param versions the deployed versions with counts, newest first (capped to the newest N)
 * @param protectedDefinition whether this key is marked protected (R-SAFE-05, #172) — every
 *     instance of it, on this engine, is refused below the ADMIN floor. Tri-state like {@code
 *     InstanceDetail.protectedInstance}: null means the protection registry couldn't be read
 *     (a Postgres outage never fails this otherwise-engine-only read; the execution-time
 *     {@code ProtectionGuard} still refuses fail-closed regardless of what this page shows)
 * @param protectionReason the reason given when protected; null when not protected or unknown
 */
public record DefinitionVersionsResponse(
        String engineId,
        String key,
        int latestVersion,
        int totalVersions,
        boolean complete,
        List<DefinitionVersion> versions,
        Boolean protectedDefinition,
        String protectionReason) {

    /**
     * @param definitionId the concrete {@code key:version:uuid} id (a pinned migration target)
     * @param runningInstanceCount RUNNING instances on this exact version right now
     * @param latest whether this is the latest deployed version
     */
    public record DefinitionVersion(
            String definitionId,
            int version,
            String name,
            String deploymentId,
            long runningInstanceCount,
            boolean latest) {}
}
