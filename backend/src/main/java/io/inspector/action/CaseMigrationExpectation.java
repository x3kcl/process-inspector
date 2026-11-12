package io.inspector.action;

import io.inspector.cmmn.CasePlanDefinitionIds;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * What a case-cohort analysis recorded for one case (#500): the definition it ran on and the
 * fingerprint of its live plan-item definition ids. The bulk item asserts both against the
 * server-fresh restatement before anything is sent.
 */
public record CaseMigrationExpectation(String fromCaseDefinitionId, String planItemStateDigest) {

    void requireUnchanged(String caseId, Map<String, Object> restated) {
        Object from = restated.get("fromCaseDefinitionId");
        Object active = restated.get("activePlanItemDefinitionIds");
        String digest = active instanceof List<?> ids
                ? CasePlanDefinitionIds.stateDigest(
                        ids.stream().map(String::valueOf).toList())
                : null;
        if (!fromCaseDefinitionId.equals(from) || !planItemStateDigest.equals(digest)) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "instance-moved-since-preview",
                    "Case " + caseId + " changed since the analysis (definition or live plan items)."
                            + " Analyze again. Nothing happened.");
        }
    }
}
