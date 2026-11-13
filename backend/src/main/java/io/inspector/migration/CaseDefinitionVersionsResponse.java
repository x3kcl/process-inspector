package io.inspector.migration;

import java.util.List;

/**
 * The case-definition versions on-ramp (#500): every deployed version of one case key on one
 * engine with its RUNNING and ended case counts. Count-only ({@code size=1} reads).
 *
 * @param complete false when the key has more versions than the on-ramp counts
 * @param definitionRoutesGate null when the engine has the 7.x-only routes (by-definition batch,
 *     historic migration). Otherwise the reason the UI shows next to the greyed controls; the BFF
 *     refuses those routes with ProblemDetail {@code version-gate} before any engine call
 */
public record CaseDefinitionVersionsResponse(
        String engineId,
        String key,
        int latestVersion,
        int totalVersions,
        boolean complete,
        List<CaseDefinitionVersion> versions,
        String definitionRoutesGate) {

    public record CaseDefinitionVersion(
            String caseDefinitionId,
            int version,
            String name,
            String deploymentId,
            long runningCaseCount,
            long endedCaseCount,
            boolean latest) {}
}
