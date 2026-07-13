package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.inspector.dto.SearchResponse.EngineResult;
import java.util.List;
import java.util.Map;

/**
 * GET /api/tasks?person= — every OPEN task assigned to, or claimable by, a person across every
 * readable engine (issue #99, "Bob is on vacation, what is he sitting on?"). Rows feed the
 * existing reassign/return-to-team verbs unchanged (SPEC §5) — this endpoint only finds the
 * targets; the corrective-action rails on those verbs are untouched. Reuses
 * {@link SearchResponse.EngineResult} for the same partial-engine-failure envelope /api/search
 * uses — an unreachable engine degrades that engine, never the whole search.
 */
public record PersonTaskSearchResponse(List<PersonTaskRow> rows, Map<String, EngineResult> perEngine) {

    /** One user task, either directly assigned or claimable via candidate-user/group. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonTaskRow(
            String engineId,
            String processInstanceId,
            String taskId,
            String taskName,
            String taskDefinitionKey,
            String processDefinitionKey,
            String assignee,
            String createTime,
            String dueDate,
            String matchReason) { // ASSIGNED | CANDIDATE

        public static final String MATCH_ASSIGNED = "ASSIGNED";
        public static final String MATCH_CANDIDATE = "CANDIDATE";
    }
}
