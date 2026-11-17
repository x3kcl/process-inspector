package io.inspector.client;

import java.util.List;

/**
 * The body of POST /query/historic-process-instances and POST /query/process-instances (#720).
 * STUB of the RED commit: the fields and the builder are final, the inclusion rules are not — an
 * unset key still serialises as {@code null} here.
 */
public record ProcessInstanceQuery(
        String processDefinitionId,
        String processDefinitionKey,
        String processBusinessKey,
        String processBusinessKeyLike,
        String superProcessInstanceId,
        List<String> processInstanceIds,
        Boolean finished,
        Boolean suspended,
        String startedAfter,
        String startedBefore,
        String tenantId,
        List<VariableFilter> variables,
        Integer start,
        Integer size,
        String sort,
        String order) {

    /** One variable predicate: {@code {name, value, operation, type}}. */
    public record VariableFilter(String name, Object value, String operation, String type) {}

    public static Builder builder() {
        return new Builder();
    }

    /** Sets only what the caller names; everything else stays unset. */
    public static final class Builder {
        private String processDefinitionId;
        private String processDefinitionKey;
        private String processBusinessKey;
        private String processBusinessKeyLike;
        private String superProcessInstanceId;
        private List<String> processInstanceIds;
        private Boolean finished;
        private Boolean suspended;
        private String startedAfter;
        private String startedBefore;
        private String tenantId;
        private List<VariableFilter> variables;
        private Integer start;
        private Integer size;
        private String sort;
        private String order;

        private Builder() {}

        public Builder processDefinitionId(String v) {
            processDefinitionId = v;
            return this;
        }

        public Builder processDefinitionKey(String v) {
            processDefinitionKey = v;
            return this;
        }

        public Builder processBusinessKey(String v) {
            processBusinessKey = v;
            return this;
        }

        public Builder processBusinessKeyLike(String v) {
            processBusinessKeyLike = v;
            return this;
        }

        public Builder superProcessInstanceId(String v) {
            superProcessInstanceId = v;
            return this;
        }

        public Builder processInstanceIds(List<String> v) {
            processInstanceIds = v;
            return this;
        }

        public Builder finished(Boolean v) {
            finished = v;
            return this;
        }

        public Builder suspended(Boolean v) {
            suspended = v;
            return this;
        }

        public Builder startedAfter(String v) {
            startedAfter = v;
            return this;
        }

        public Builder startedBefore(String v) {
            startedBefore = v;
            return this;
        }

        /** A blank tenant is no tenant filter — the key is left out. */
        public Builder tenantId(String v) {
            tenantId = v != null && !v.isBlank() ? v : null;
            return this;
        }

        public Builder variables(List<VariableFilter> v) {
            variables = v;
            return this;
        }

        public Builder start(Integer v) {
            start = v;
            return this;
        }

        public Builder size(Integer v) {
            size = v;
            return this;
        }

        public Builder sort(String sortKey, String direction) {
            sort = sortKey;
            order = direction;
            return this;
        }

        public ProcessInstanceQuery build() {
            return new ProcessInstanceQuery(
                    processDefinitionId,
                    processDefinitionKey,
                    processBusinessKey,
                    processBusinessKeyLike,
                    superProcessInstanceId,
                    processInstanceIds,
                    finished,
                    suspended,
                    startedAfter,
                    startedBefore,
                    tenantId,
                    variables,
                    start,
                    size,
                    sort,
                    order);
        }
    }
}
