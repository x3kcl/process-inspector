package io.inspector.detail;

import io.inspector.client.ExecutionRecord;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.HistoricVariableRecord;
import io.inspector.client.ProcessInstanceRecord;
import io.inspector.client.VariableRecord;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceVariables;
import io.inspector.dto.InstanceVariables.ExecutionScope;
import io.inspector.dto.InstanceVariables.VariableDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Variables tab. Moved from {@link InstanceDetailService} (issue #475). */
final class InstanceVariablesAssembler {

    private final InstanceDetailService host;

    InstanceVariablesAssembler(InstanceDetailService host) {
        this.host = host;
    }

    /* ================= variables — the typed ledger ================= */

    public InstanceVariables variables(String engineId, String instanceId) {
        EngineConfig engine = host.engineForRead(engineId);
        ProcessInstanceRecord historic = host.requireHistoric(engine, instanceId);
        if (historic.endTime() != null) {
            return historicVariables(engine, instanceId);
        }

        List<VariableRecord> processRows =
                host.flowable.listInstanceVariables(engine, CallPriority.INTERACTIVE, instanceId);
        if (processRows == null) {
            // Ended between the historic read and now — serve the historic projection.
            return historicVariables(engine, instanceId);
        }
        List<VariableDto> processScope = processRows.stream()
                .map(row -> InstanceDetailService.typedRow(row, row.scope(), null, null))
                .sorted(Comparator.comparing(VariableDto::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Execution-local segregation (SPEC §4): every non-root execution may hold locals —
        // multi-instance loop variables live there. Empty scopes are dropped, not rendered.
        List<ExecutionScope> executionScopes = new ArrayList<>();
        for (ExecutionRecord execution : host.flowable
                .listExecutions(engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                .dataOrEmpty()) {
            String executionId = execution.id();
            if (executionId == null || executionId.equals(instanceId)) continue; // root = process scope
            List<VariableDto> locals =
                    host.flowable.listExecutionLocalVariables(engine, CallPriority.INTERACTIVE, executionId).stream()
                            .map(row -> InstanceDetailService.typedRow(row, "local", executionId, null))
                            .sorted(Comparator.comparing(
                                    VariableDto::name, Comparator.nullsLast(Comparator.naturalOrder())))
                            .toList();
            if (!locals.isEmpty()) {
                executionScopes.add(
                        new ExecutionScope(executionId, execution.activityId(), execution.parentId(), locals));
            }
        }
        return new InstanceVariables("RUNTIME", processScope, executionScopes);
    }

    /**
     * On-demand full value (SPEC §4a: an edit always operates on the fetched full value).
     * Scope-aware: a blank {@code executionId} reads the process (case) scope; a non-blank one
     * reads the execution-local ("step-local") variable {@code scope=local} on that node — the
     * base value the step-local editor stages and CASes against.
     */
    public VariableDto variable(String engineId, String instanceId, String name, String executionId) {
        EngineConfig engine = host.engineForRead(engineId);
        host.requireHistoric(engine, instanceId);
        boolean local = executionId != null && !executionId.isBlank();
        VariableRecord row = local
                ? host.flowable.getExecutionVariable(engine, CallPriority.INTERACTIVE, executionId, name)
                : host.flowable.getInstanceVariable(engine, CallPriority.INTERACTIVE, instanceId, name);
        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    local
                            ? "step-local variable '" + name + "' not found on execution " + executionId
                            : "variable '" + name + "' not found on running instance " + engineId + ":" + instanceId);
        }
        // The full fetch is deliberately uncapped — this IS the "load full value" escape
        // hatch; the write cap (5 MiB, SPEC §4a) guards the other direction.
        return new VariableDto(
                row.name(),
                row.type(),
                row.value(),
                false,
                null,
                local ? "local" : row.scope(),
                local ? executionId : null,
                null);
    }

    private InstanceVariables historicVariables(EngineConfig engine, String instanceId) {
        List<VariableDto> rows = new ArrayList<>();
        for (HistoricVariableRecord row : host.flowable
                .listHistoricVariableInstances(
                        engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                .dataOrEmpty()) {
            if (row.variable() == null) continue; // every recorded engine nests the envelope
            rows.add(InstanceDetailService.typedRow(row.variable(), "global", row.executionId(), row.taskId()));
        }
        rows.sort(Comparator.comparing(VariableDto::name, Comparator.nullsLast(Comparator.naturalOrder())));
        return new InstanceVariables("HISTORIC", rows, List.of());
    }
}
