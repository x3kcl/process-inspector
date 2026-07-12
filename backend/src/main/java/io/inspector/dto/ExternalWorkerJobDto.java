package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One external-worker job row (v1.x #7) — Flowable's fifth job queue, distinct from the four
 * management lanes because it carries the WORKER LOCK ({@code lockOwner} /
 * {@code lockExpirationTime}): who is holding the task and until when. That lock is the whole
 * diagnosis for a "stuck at an external worker" incident — a job with a {@code lockOwner} whose
 * {@code lockExpirationTime} keeps sliding is a worker that acquired but never completes; a job
 * with {@code retries == 0} and an {@code exceptionMessage} is a worker that keeps failing it.
 *
 * <p>Read-only for v1.x (no retry/unacquire/terminate). Sourced from the External Worker REST
 * API's {@code GET …/external-job-api/jobs}, NOT the management API — see
 * {@code ExternalJobApiClient.listExternalWorkerJobs}. Nullable fields differ by engine.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalWorkerJobDto(
        String id,
        String elementId, // the external-worker service task
        String elementName,
        String lockOwner, // null until a worker acquires it — the "who's holding this" tell
        String lockExpirationTime, // when the lock lapses and the job is re-acquirable
        Integer retries,
        String exceptionMessage, // first-line snippet of the worker's reported failure, if any
        String createTime,
        String dueDate,
        String executionId,
        String processDefinitionId,
        String tenantId) {}
