package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The process-api facade (Engine-client split, #86 — F2/F9): process-instance/history queries,
 * job/DLQ management, tasks, variables, executions, process-definitions, and every corrective-action
 * mutation, all against the engine's {@code /service} base. Every method threads {@link CallPriority}
 * explicitly (uniform, second positional param after {@code engine}) — no priority-less convenience
 * overload, so a caller can never silently land on the interactive lane by omission the way the old
 * god-class let CMMN/external-worker callers do.
 *
 * <p>Reads go through {@link GuardedCaller#readClient}; mutations (the {@code void}-returning verbs)
 * go through {@link GuardedCaller#writeClient} so write-ms (R-NFR-07) budgets them separately and are
 * NEVER retried here or anywhere upstream — a timed-out mutation is reported UNKNOWN, not re-fired
 * (corrective-actions skill §4).
 */
@Component
public class ProcessApiClient {

    private final ProcessInstanceReadClient reads;
    private final ProcessHealthClient health;
    private final ProcessCorrectiveClient actions;
    private final ProcessRepositoryClient repository;

    public ProcessApiClient(GuardedCaller guarded) {
        this.reads = new ProcessInstanceReadClient(guarded);
        this.health = new ProcessHealthClient(guarded);
        this.actions = new ProcessCorrectiveClient(guarded);
        this.repository = new ProcessRepositoryClient(guarded);
    }

    /** The four job queues (flowable-rest skill §3) and their management paths. */
    public enum JobLaneKind {
        EXECUTABLE("/management/jobs"),
        TIMER("/management/timer-jobs"),
        SUSPENDED("/management/suspended-jobs"),
        DEADLETTER("/management/deadletter-jobs"),
        /** Async-history backlog. Absent on engines that predate the route — callers treat 404 as not available. */
        HISTORY("/management/history-jobs");

        final String path;

        JobLaneKind(String path) {
            this.path = path;
        }
    }

    public ProcessInstancePage queryHistoricProcessInstances(
            EngineConfig engine, CallPriority priority, ProcessInstanceQuery body) {
        return reads.queryHistoricProcessInstances(engine, priority, body);
    }

    public ProcessInstancePage queryRuntimeProcessInstances(
            EngineConfig engine, CallPriority priority, ProcessInstanceQuery body) {
        return reads.queryRuntimeProcessInstances(engine, priority, body);
    }

    public JobPage listJobs(
            EngineConfig engine,
            CallPriority priority,
            JobLaneKind lane,
            Map<String, String> filters,
            int start,
            int size) {
        return reads.listJobs(engine, priority, lane, filters, start, size);
    }

    public ProcessInstanceRecord getHistoricProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        return reads.getHistoricProcessInstance(engine, priority, processInstanceId);
    }

    public ProcessInstanceRecord getRuntimeProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        return reads.getRuntimeProcessInstance(engine, priority, processInstanceId);
    }

    public ActivityInstancePage listUnfinishedActivities(
            EngineConfig engine, CallPriority priority, String processInstanceId, String tenantId, int size) {
        return reads.listUnfinishedActivities(engine, priority, processInstanceId, tenantId, size);
    }

    public ProcessDefinitionPage listProcessDefinitionsByKey(
            EngineConfig engine, CallPriority priority, String key, Integer version, int start, int size) {
        return reads.listProcessDefinitionsByKey(engine, priority, key, version, start, size);
    }

    public ProcessDefinitionPage listProcessDefinitionVersionsDesc(
            EngineConfig engine, CallPriority priority, String key, int start, int size) {
        return reads.listProcessDefinitionVersionsDesc(engine, priority, key, start, size);
    }

    public ProcessDefinitionPage latestProcessDefinitionByKey(EngineConfig engine, CallPriority priority, String key) {
        return reads.latestProcessDefinitionByKey(engine, priority, key);
    }

    public ProcessDefinitionPage listLatestProcessDefinitions(EngineConfig engine, CallPriority priority, int size) {
        return reads.listLatestProcessDefinitions(engine, priority, size);
    }

    public String jobExceptionStacktrace(EngineConfig engine, CallPriority priority, JobLaneKind lane, String jobId) {
        return reads.jobExceptionStacktrace(engine, priority, lane, jobId);
    }

    public ExecutionPage listExecutions(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return reads.listExecutions(engine, priority, processInstanceId, size);
    }

    public List<IdentityLinkRecord> listRuntimeIdentityLinks(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        return reads.listRuntimeIdentityLinks(engine, priority, processInstanceId);
    }

    public List<IdentityLinkRecord> listHistoricIdentityLinks(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        return reads.listHistoricIdentityLinks(engine, priority, processInstanceId);
    }

    public void addInstanceIdentityLink(
            EngineConfig engine, CallPriority priority, String processInstanceId, String user, String type) {
        reads.addInstanceIdentityLink(engine, priority, processInstanceId, user, type);
    }

    public void deleteInstanceIdentityLink(
            EngineConfig engine, CallPriority priority, String processInstanceId, String user, String type) {
        reads.deleteInstanceIdentityLink(engine, priority, processInstanceId, user, type);
    }

    public List<VariableRecord> listInstanceVariables(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        return reads.listInstanceVariables(engine, priority, processInstanceId);
    }

    public List<VariableRecord> listExecutionLocalVariables(
            EngineConfig engine, CallPriority priority, String executionId) {
        return reads.listExecutionLocalVariables(engine, priority, executionId);
    }

    public HistoricVariablePage listHistoricVariableInstances(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return reads.listHistoricVariableInstances(engine, priority, processInstanceId, size);
    }

    public HistoricVariablePage listProcessScopeHistoricVariables(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return reads.listProcessScopeHistoricVariables(engine, priority, processInstanceId, size);
    }

    public ActivityInstancePage listHistoricActivities(
            EngineConfig engine, CallPriority priority, String processInstanceId, int start, int size) {
        return reads.listHistoricActivities(engine, priority, processInstanceId, start, size);
    }

    public EventSubscriptionPage listEventSubscriptions(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return reads.listEventSubscriptions(engine, priority, processInstanceId, size);
    }

    public TaskPage listHistoricTaskInstances(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return reads.listHistoricTaskInstances(engine, priority, processInstanceId, size);
    }

    public TaskPage listRuntimeTasks(EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return reads.listRuntimeTasks(engine, priority, processInstanceId, size);
    }

    public TaskPage listRuntimeTasks(EngineConfig engine, CallPriority priority, Map<String, String> query, int size) {
        return reads.listRuntimeTasks(engine, priority, query, size);
    }

    public TaskPage listTasksByAssignee(EngineConfig engine, CallPriority priority, String assignee, int size) {
        return reads.listTasksByAssignee(engine, priority, assignee, size);
    }

    public TaskPage listTasksByCandidateUser(
            EngineConfig engine, CallPriority priority, String candidateUser, int size) {
        return reads.listTasksByCandidateUser(engine, priority, candidateUser, size);
    }

    public TaskRecord getHistoricTaskInstance(EngineConfig engine, CallPriority priority, String taskId) {
        return reads.getHistoricTaskInstance(engine, priority, taskId);
    }

    public String deploymentResourceData(
            EngineConfig engine, CallPriority priority, String deploymentId, String resourceName) {
        return reads.deploymentResourceData(engine, priority, deploymentId, resourceName);
    }

    public Map<String, Object> getMap(EngineConfig engine, CallPriority priority, String path) {
        return reads.getMap(engine, priority, path);
    }

    public List<TableCountRecord> listTables(EngineConfig engine, CallPriority priority) {
        return reads.listTables(engine, priority);
    }

    public EngineInfoRecord engineInfo(EngineConfig engine, CallPriority priority) {
        return reads.engineInfo(engine, priority);
    }

    public long countJobs(EngineConfig engine, CallPriority priority, JobLaneKind lane) {
        return health.countJobs(engine, priority, lane);
    }

    public Long countJobsIfSupported(EngineConfig engine, CallPriority priority, JobLaneKind lane) {
        return health.countJobsIfSupported(engine, priority, lane);
    }

    public JobRecord oldestHistoryJob(EngineConfig engine, CallPriority priority) {
        return health.oldestHistoryJob(engine, priority);
    }

    public Long countHistoricProcesses(
            EngineConfig engine,
            CallPriority priority,
            Instant after,
            Instant before,
            boolean started,
            boolean rootOnly) {
        return health.countHistoricProcesses(engine, priority, after, before, started, rootOnly);
    }

    public Long countHistoricProcesses(
            EngineConfig engine,
            CallPriority priority,
            Instant after,
            Instant before,
            boolean started,
            boolean rootOnly,
            String definitionKey) {
        return health.countHistoricProcesses(engine, priority, after, before, started, rootOnly, definitionKey);
    }

    public ProcessInstancePage listRuntimeInstances(
            EngineConfig engine,
            CallPriority priority,
            String processDefinitionId,
            String businessKey,
            String businessKeyLike,
            String startedAfter,
            String startedBefore,
            int size) {
        return health.listRuntimeInstances(
                engine, priority, processDefinitionId, businessKey, businessKeyLike, startedAfter, startedBefore, size);
    }

    public ProcessInstancePage listFinishedProcesses(
            EngineConfig engine,
            CallPriority priority,
            String definitionKey,
            String businessKey,
            String finishedBefore,
            int start,
            int size) {
        return health.listFinishedProcesses(engine, priority, definitionKey, businessKey, finishedBefore, start, size);
    }

    public void deleteHistoricProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId, String deleteReason) {
        health.deleteHistoricProcessInstance(engine, priority, processInstanceId, deleteReason);
    }

    public Long countHistoricTasks(
            EngineConfig engine, CallPriority priority, Instant after, Instant before, boolean completed) {
        return health.countHistoricTasks(engine, priority, after, before, completed);
    }

    public JobRecord oldestExecutableJob(EngineConfig engine, CallPriority priority) {
        return health.oldestExecutableJob(engine, priority);
    }

    public long countOverdueTimers(EngineConfig engine, CallPriority priority, Instant dueBefore) {
        return health.countOverdueTimers(engine, priority, dueBefore);
    }

    public boolean probeActivityHistory(EngineConfig engine, CallPriority priority) {
        return health.probeActivityHistory(engine, priority);
    }

    public JobRecord getJob(EngineConfig engine, CallPriority priority, JobLaneKind lane, String jobId) {
        return actions.getJob(engine, priority, lane, jobId);
    }

    public void moveDeadLetterJob(EngineConfig engine, CallPriority priority, String jobId) {
        actions.moveDeadLetterJob(engine, priority, jobId);
    }

    public void rescheduleTimer(EngineConfig engine, CallPriority priority, String jobId, String dueDate) {
        actions.rescheduleTimer(engine, priority, jobId, dueDate);
    }

    public void moveDeadLetterToHistoryJob(EngineConfig engine, CallPriority priority, String jobId) {
        actions.moveDeadLetterToHistoryJob(engine, priority, jobId);
    }

    public void deleteJob(EngineConfig engine, CallPriority priority, JobLaneKind lane, String jobId) {
        actions.deleteJob(engine, priority, lane, jobId);
    }

    public void moveTimerJob(EngineConfig engine, CallPriority priority, String jobId) {
        actions.moveTimerJob(engine, priority, jobId);
    }

    public void deleteDeadLetterJob(EngineConfig engine, CallPriority priority, String jobId) {
        actions.deleteDeadLetterJob(engine, priority, jobId);
    }

    public void suspendOrActivateInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId, String action) {
        actions.suspendOrActivateInstance(engine, priority, processInstanceId, action);
    }

    public void deleteProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId, String deleteReason) {
        actions.deleteProcessInstance(engine, priority, processInstanceId, deleteReason);
    }

    public VariableRecord getInstanceVariable(
            EngineConfig engine, CallPriority priority, String processInstanceId, String name) {
        return actions.getInstanceVariable(engine, priority, processInstanceId, name);
    }

    public void putInstanceVariable(
            EngineConfig engine,
            CallPriority priority,
            String processInstanceId,
            String name,
            VariableWriteRequest typedVariable) {
        actions.putInstanceVariable(engine, priority, processInstanceId, name, typedVariable);
    }

    public VariableRecord getExecutionVariable(
            EngineConfig engine, CallPriority priority, String executionId, String name) {
        return actions.getExecutionVariable(engine, priority, executionId, name);
    }

    public void putExecutionVariable(
            EngineConfig engine,
            CallPriority priority,
            String executionId,
            String name,
            VariableWriteRequest typedVariable) {
        actions.putExecutionVariable(engine, priority, executionId, name, typedVariable);
    }

    public TaskRecord getTask(EngineConfig engine, CallPriority priority, String taskId) {
        return actions.getTask(engine, priority, taskId);
    }

    public void completeTask(
            EngineConfig engine, CallPriority priority, String taskId, List<TaskActionRequest.Variable> variables) {
        actions.completeTask(engine, priority, taskId, variables);
    }

    public void setTaskAssignee(EngineConfig engine, CallPriority priority, String taskId, String assignee) {
        actions.setTaskAssignee(engine, priority, taskId, assignee);
    }

    public void taskAction(EngineConfig engine, CallPriority priority, String taskId, String action, String assignee) {
        actions.taskAction(engine, priority, taskId, action, assignee);
    }

    public void putTask(EngineConfig engine, CallPriority priority, String taskId, TaskUpdateRequest body) {
        actions.putTask(engine, priority, taskId, body);
    }

    public void deleteTask(EngineConfig engine, CallPriority priority, String taskId) {
        actions.deleteTask(engine, priority, taskId);
    }

    public List<IdentityLinkRecord> listIdentityLinks(EngineConfig engine, CallPriority priority, String taskId) {
        return actions.listIdentityLinks(engine, priority, taskId);
    }

    public void addIdentityLink(
            EngineConfig engine, CallPriority priority, String taskId, String family, String identityId, String type) {
        actions.addIdentityLink(engine, priority, taskId, family, identityId, type);
    }

    public void deleteIdentityLink(
            EngineConfig engine, CallPriority priority, String taskId, String family, String identityId, String type) {
        actions.deleteIdentityLink(engine, priority, taskId, family, identityId, type);
    }

    public boolean idmIdentityExists(EngineConfig engine, CallPriority priority, String family, String identityId) {
        return actions.idmIdentityExists(engine, priority, family, identityId);
    }

    public boolean probeIdm(EngineConfig engine, CallPriority priority) {
        return actions.probeIdm(engine, priority);
    }

    public ExecutionRecord getExecution(EngineConfig engine, CallPriority priority, String executionId) {
        return actions.getExecution(engine, priority, executionId);
    }

    public void executionAction(
            EngineConfig engine, CallPriority priority, String executionId, Map<String, Object> body) {
        actions.executionAction(engine, priority, executionId, body);
    }

    public ProcessDefinitionRecord getProcessDefinition(
            EngineConfig engine, CallPriority priority, String definitionId) {
        return repository.getProcessDefinition(engine, priority, definitionId);
    }

    public void suspendOrActivateDefinition(
            EngineConfig engine,
            CallPriority priority,
            String definitionId,
            String action,
            boolean includeProcessInstances) {
        repository.suspendOrActivateDefinition(engine, priority, definitionId, action, includeProcessInstances);
    }

    public void suspendOrActivateDefinition(
            EngineConfig engine,
            CallPriority priority,
            String definitionId,
            String action,
            boolean includeProcessInstances,
            String effectiveAt) {
        repository.suspendOrActivateDefinition(
                engine, priority, definitionId, action, includeProcessInstances, effectiveAt);
    }

    public Map<String, Object> getProcessDefinitionModel(
            EngineConfig engine, CallPriority priority, String definitionId) {
        return repository.getProcessDefinitionModel(engine, priority, definitionId);
    }

    public String processDefinitionResourceData(EngineConfig engine, CallPriority priority, String definitionId) {
        return repository.processDefinitionResourceData(engine, priority, definitionId);
    }

    public void changeActivityState(
            EngineConfig engine, CallPriority priority, String processInstanceId, ChangeActivityStateRequest body) {
        repository.changeActivityState(engine, priority, processInstanceId, body);
    }

    public void migrateInstance(
            EngineConfig engine,
            CallPriority priority,
            String processInstanceId,
            Map<String, Object> migrationDocument) {
        repository.migrateInstance(engine, priority, processInstanceId, migrationDocument);
    }

    public ProcessInstanceRecord startProcessInstance(
            EngineConfig engine, CallPriority priority, StartProcessInstanceRequest body) {
        return repository.startProcessInstance(engine, priority, body);
    }

    public ProcessDefinitionPage listProcessDefinitions(
            EngineConfig engine,
            CallPriority priority,
            String key,
            String nameLike,
            Boolean latest,
            String deploymentId,
            int start,
            int size) {
        return repository.listProcessDefinitions(engine, priority, key, nameLike, latest, deploymentId, start, size);
    }

    public EventSubscriptionPage listDefinitionEventSubscriptions(
            EngineConfig engine, CallPriority priority, String processDefinitionId, int size) {
        return repository.listDefinitionEventSubscriptions(engine, priority, processDefinitionId, size);
    }

    public DeploymentPage listDeployments(
            EngineConfig engine, CallPriority priority, String nameLike, int start, int size) {
        return repository.listDeployments(engine, priority, nameLike, start, size);
    }

    public DeploymentRecord getDeployment(EngineConfig engine, CallPriority priority, String deploymentId) {
        return repository.getDeployment(engine, priority, deploymentId);
    }

    public List<DeploymentResourceRecord> listDeploymentResources(
            EngineConfig engine, CallPriority priority, String deploymentId) {
        return repository.listDeploymentResources(engine, priority, deploymentId);
    }

    public void deleteDeployment(EngineConfig engine, CallPriority priority, String deploymentId, boolean cascade) {
        repository.deleteDeployment(engine, priority, deploymentId, cascade);
    }
}
