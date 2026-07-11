package io.inspector.detail;

import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.dto.ExternalWorkerJobDto;
import io.inspector.dto.InstanceDetail;
import io.inspector.dto.InstanceDiagram;
import io.inspector.dto.InstanceHierarchy;
import io.inspector.dto.InstanceJobs;
import io.inspector.dto.InstanceTasks;
import io.inspector.dto.InstanceTimeline;
import io.inspector.dto.InstanceVariables;
import io.inspector.dto.InstanceVariables.VariableDto;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Stage 2 detail resource (SPEC §4, ARCH §4): read-only per-instance views under the
 * composite path {@code /api/instances/{engineId}/{instanceId}}. VIEWER floor per engine —
 * the same read gate as notes/audit. Every endpoint is historic-first (a completed
 * instance renders, never 404s) and 404s with a ProblemDetail when the instance is
 * genuinely unknown to the engine.
 */
@RestController
@RequestMapping("/api/instances/{engineId}/{instanceId}")
public class InstanceDetailController {

    private final InstanceDetailService detail;
    private final StatusEvidenceService statusEvidence;

    public InstanceDetailController(InstanceDetailService detail, StatusEvidenceService statusEvidence) {
        this.detail = detail;
        this.statusEvidence = statusEvidence;
    }

    /** Vitals: identity, definition+version, flags, current activity, why-stuck, waiting-for. */
    @GetMapping
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public InstanceDetail vitals(@PathVariable String engineId, @PathVariable String instanceId) {
        return detail.vitals(engineId, instanceId);
    }

    /**
     * "Explain this status" (R-L3-01, SPEC §3): the falsifiable derivation behind the status
     * chip — the plan shape chosen and why, each engine call's URL/body/status/duration/asOf,
     * and per-flag provenance, re-derived on demand and labeled as such. VIEWER floor, like
     * every other detail read.
     */
    @GetMapping("/explain-status")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public io.inspector.dto.StatusEvidence explainStatus(
            @PathVariable String engineId, @PathVariable String instanceId) {
        return statusEvidence.explain(engineId, instanceId);
    }

    /** BPMN 2.0 XML exactly as deployed + marker id sets for the bpmn-js overlays. */
    @GetMapping("/diagram")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public InstanceDiagram diagram(@PathVariable String engineId, @PathVariable String instanceId) {
        return detail.diagram(engineId, instanceId);
    }

    /** The typed variable ledger (R-UXQ-13) — process scope + per-execution locals. */
    @GetMapping("/variables")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public InstanceVariables variables(@PathVariable String engineId, @PathVariable String instanceId) {
        return detail.variables(engineId, instanceId);
    }

    /**
     * The on-demand FULL value behind a truncated ledger row (SPEC §4 size safeguards).
     * {@code executionId} (optional) scopes the read to an execution-local ("step-local")
     * variable — the base value the step-local editor stages against; omitted = process scope.
     */
    @GetMapping("/variables/{name}")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public VariableDto variable(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @PathVariable String name,
            @RequestParam(required = false) String executionId) {
        return detail.variable(engineId, instanceId, name, executionId);
    }

    /** The four job lanes, kept distinct — the lane IS the diagnosis (SPEC §4). */
    @GetMapping("/jobs")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public InstanceJobs jobs(@PathVariable String engineId, @PathVariable String instanceId) {
        return detail.jobs(engineId, instanceId);
    }

    /**
     * External-worker jobs — Flowable's fifth queue (v1.x #7), read-only. Capability-gated
     * (≥ 6.8): a pre-6.8 engine gets a ProblemDetail, never a masking empty list.
     */
    @GetMapping("/jobs/external-worker")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public java.util.List<ExternalWorkerJobDto> externalWorkerJobs(
            @PathVariable String engineId, @PathVariable String instanceId) {
        return detail.externalWorkerJobs(engineId, instanceId);
    }

    /** Stacktrace on expand, plain text. {@code lane} names the queue the job sits in. */
    @GetMapping(value = "/jobs/{jobId}/stacktrace", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public String jobStacktrace(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @PathVariable String jobId,
            @RequestParam(defaultValue = "DEADLETTER") String lane) {
        JobLaneKind kind;
        try {
            kind = JobLaneKind.valueOf(lane.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown job lane '" + lane + "' — one of EXECUTABLE, TIMER, SUSPENDED, DEADLETTER");
        }
        return detail.jobStacktrace(engineId, instanceId, jobId, kind);
    }

    /** User tasks, completed AND open — historic ∪ runtime, suspension state derived. */
    @GetMapping("/tasks")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public InstanceTasks tasks(@PathVariable String engineId, @PathVariable String instanceId) {
        return detail.tasks(engineId, instanceId);
    }

    /** The call-activity tree, both directions — depth 10 / breadth 50, counts exact. */
    @GetMapping("/hierarchy")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public InstanceHierarchy hierarchy(@PathVariable String engineId, @PathVariable String instanceId) {
        return detail.hierarchy(engineId, instanceId);
    }

    /** Historic activity instances, startTime ascending — the Gantt rows. */
    @GetMapping("/timeline")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public InstanceTimeline timeline(@PathVariable String engineId, @PathVariable String instanceId) {
        return detail.timeline(engineId, instanceId);
    }

    /** Bad lane names and friends are the caller's mistake — 400, not 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
