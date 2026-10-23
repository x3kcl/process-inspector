package io.inspector.detail;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.ExternalWorkerJobDto;
import io.inspector.dto.InstanceJobs;
import io.inspector.dto.InstanceJobs.JobDto;
import io.inspector.registry.EngineCapabilities;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Jobs tab, including external-worker jobs. Moved from {@link InstanceDetailService} (issue #475). */
final class InstanceJobsAssembler {

    private final InstanceDetailService host;

    InstanceJobsAssembler(InstanceDetailService host) {
        this.host = host;
    }

    /* ================= jobs — four lanes, kept distinct ================= */

    public InstanceJobs jobs(String engineId, String instanceId) {
        EngineConfig engine = host.engineForRead(engineId);
        host.requireHistoric(engine, instanceId);
        return new InstanceJobs(
                laneRows(engine, instanceId, JobLaneKind.EXECUTABLE),
                laneRows(engine, instanceId, JobLaneKind.TIMER),
                laneRows(engine, instanceId, JobLaneKind.SUSPENDED),
                laneRows(engine, instanceId, JobLaneKind.DEADLETTER));
    }

    /** Plain-text stacktrace, fetched on expand (SPEC §4) — 404 when the job moved on. */
    public String jobStacktrace(String engineId, String instanceId, String jobId, JobLaneKind lane) {
        EngineConfig engine = host.engineForRead(engineId);
        host.requireHistoric(engine, instanceId);
        String stacktrace = host.flowable.jobExceptionStacktrace(engine, CallPriority.INTERACTIVE, lane, jobId);
        if (stacktrace == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "no stacktrace for job " + jobId + " in the " + lane + " lane (retried or deleted?)");
        }
        return stacktrace;
    }

    private List<JobDto> laneRows(EngineConfig engine, String instanceId, JobLaneKind lane) {
        return host.instanceJobs(engine, instanceId, lane).stream()
                .map(job -> InstanceDetailService.jobRow(job, lane))
                .toList();
    }

    /* ============ external-worker jobs — the fifth queue (v1.x #7) ============ */

    /**
     * External-worker jobs for the instance (read-only). Capability-gated (Flowable ≥ 6.8):
     * on an older engine the BFF refuses with a ProblemDetail rather than letting the call die
     * as a confusing 404 at the sibling external-job-api context. The UI never reaches here on
     * a pre-6.8 engine — it reads the same flag off {@code EngineDto.capabilities} — but the
     * server stays the gate.
     */
    public List<ExternalWorkerJobDto> externalWorkerJobs(String engineId, String instanceId) {
        EngineConfig engine = host.engineForRead(engineId);
        requireExternalWorkerCapability(engine);
        host.requireHistoric(engine, instanceId);
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("processInstanceId", instanceId);
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }
        return host
                .externalJobs
                .listExternalWorkerJobs(engine, CallPriority.INTERACTIVE, filters, 0, engine.maxPageSizeOrDefault())
                .dataOrEmpty()
                .stream()
                .map(InstanceDetailService::externalWorkerRow)
                .toList();
    }

    private void requireExternalWorkerCapability(EngineConfig engine) {
        EngineCapabilities capabilities = host.registry.healthOf(engine.id()).capabilities();
        if (capabilities == null) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unknown",
                    "Engine '" + engine.id() + "' has not answered a health probe yet — external-worker support"
                            + " (Flowable ≥ 6.8) is unverified, so the call is refused rather than sent blind.");
        }
        if (!capabilities.externalWorkerJobs()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unavailable",
                    "Engine '" + engine.id() + "' runs an Unsupported Engine Version for external-worker jobs"
                            + " (requires Flowable ≥ 6.8) — refused in the BFF, never a confusing engine 404.");
        }
    }
}
