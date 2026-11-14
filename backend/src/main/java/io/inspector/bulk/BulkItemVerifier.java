package io.inspector.bulk;

import io.inspector.action.ActionVerb;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.JobRecord;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.migration.CaseCohortBulkMigration;
import io.inspector.migration.CohortBulkMigration;
import java.util.Map;

/**
 * Verify-now predicates (R-SAFE-09): one read of live engine state per verb, never a re-fire.
 * Moved from {@link BulkJobService} (issue #475).
 */
final class BulkItemVerifier {

    private final BulkJobService host;

    BulkItemVerifier(BulkJobService host) {
        this.host = host;
    }

    Verdict verdict(EngineConfig engine, BulkJob job, BulkJobItem item) {
        if (BulkJobService.MIGRATE_VERB.equals(job.getVerb())) {
            return migrated(item);
        }
        if (BulkJobService.MIGRATE_CASE_VERB.equals(job.getVerb())) {
            return caseMigrated(item);
        }
        return precondition(engine, ActionVerb.fromPath(job.getVerb()).orElseThrow(), item, job);
    }

    record Verdict(BulkJobItem.State reclassifyTo, String evidence) {}

    /** migrate-instance: on the analysis target ⇒ ok; anything else stays unknown with evidence. */
    private Verdict migrated(BulkJobItem item) {
        CohortBulkMigration.Verification v =
                host.cohortMigration.verify(item.getJobRef(), item.getEngineId(), item.getInstanceId());
        return new Verdict(v.migrated() ? BulkJobItem.State.ok : null, v.evidence());
    }

    /** migrate-case: on the analysis target ⇒ ok; anything else stays unknown with evidence. */
    private Verdict caseMigrated(BulkJobItem item) {
        CaseCohortBulkMigration.Verification v =
                host.caseCohortMigration.verify(item.getJobRef(), item.getEngineId(), item.getInstanceId());
        return new Verdict(v.migrated() ? BulkJobItem.State.ok : null, v.evidence());
    }

    private Verdict precondition(EngineConfig engine, ActionVerb verb, BulkJobItem item, BulkJob job) {
        switch (verb) {
            case RETRY_JOB -> {
                if (item.getJobRef() == null) {
                    return new Verdict(null, "no job reference recorded — needs L3 review of the audit trail");
                }
                JobRecord dlq =
                        host.client.getJob(engine, CallPriority.INTERACTIVE, JobLaneKind.DEADLETTER, item.getJobRef());
                return dlq == null
                        ? new Verdict(
                                BulkJobItem.State.ok,
                                "verified: job " + item.getJobRef() + " is no longer dead-lettered")
                        : new Verdict(
                                null,
                                "job " + item.getJobRef()
                                        + " is STILL dead-lettered — the retry may not have applied (or it failed"
                                        + " again); needs L3");
            }
            case TRIGGER_TIMER -> {
                if (item.getJobRef() == null) {
                    return new Verdict(null, "no timer reference recorded — needs L3 review of the audit trail");
                }
                JobRecord timer =
                        host.client.getJob(engine, CallPriority.INTERACTIVE, JobLaneKind.TIMER, item.getJobRef());
                return timer == null
                        ? new Verdict(BulkJobItem.State.ok, "verified: timer " + item.getJobRef() + " has fired")
                        : new Verdict(null, "timer " + item.getJobRef() + " is still queued — still-pending");
            }
            case SUSPEND, ACTIVATE -> {
                Map<String, Object> instance =
                        host.client.getRuntimeProcessInstance(engine, CallPriority.INTERACTIVE, item.getInstanceId());
                if (instance == null) {
                    return new Verdict(null, "instance is no longer running — completed or deleted since; needs L3");
                }
                boolean suspended = Boolean.TRUE.equals(instance.get("suspended"));
                boolean wanted = verb == ActionVerb.SUSPEND;
                return suspended == wanted
                        ? new Verdict(
                                BulkJobItem.State.ok, "verified: instance is " + (suspended ? "suspended" : "active"))
                        : new Verdict(
                                null,
                                "instance is " + (suspended ? "suspended" : "active") + " — the " + verb.path()
                                        + " likely did not apply; still-pending");
            }
            case DELETE_HISTORY -> {
                Map<String, Object> row =
                        host.client.getHistoricProcessInstance(engine, CallPriority.HOUSEKEEPING, item.getInstanceId());
                if (row == null) {
                    return new Verdict(BulkJobItem.State.ok, "verified: historic instance is gone");
                }
                if (row.get("endTime") == null) {
                    return new Verdict(null, "instance is still running");
                }
                return new Verdict(null, "historic instance is still present");
            }
            case REASSIGN_TASK, UNASSIGN_TASK -> {
                if (item.getJobRef() == null) {
                    return new Verdict(null, "no task reference recorded — needs L3 review of the audit trail");
                }
                Map<String, Object> task = host.client.getTask(engine, CallPriority.INTERACTIVE, item.getJobRef());
                if (task == null) {
                    return new Verdict(BulkJobItem.State.skipped, "task " + item.getJobRef() + " is no longer open");
                }
                String assignee = task.get("assignee") != null ? String.valueOf(task.get("assignee")) : null;
                if (verb == ActionVerb.UNASSIGN_TASK) {
                    return assignee == null
                            ? new Verdict(BulkJobItem.State.ok, "verified: task is unassigned")
                            : new Verdict(null, "task is still assigned to " + assignee);
                }
                return job.getAssignee() != null && job.getAssignee().equals(assignee)
                        ? new Verdict(BulkJobItem.State.ok, "verified: assignee is " + assignee)
                        : new Verdict(null, "assignee is " + assignee + " — reassign may not have applied");
            }
            default -> {
                return new Verdict(null, "no precondition predicate for '" + verb.path() + "' — needs L3");
            }
        }
    }
}
