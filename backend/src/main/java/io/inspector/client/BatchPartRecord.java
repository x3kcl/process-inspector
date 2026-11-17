package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One part of an engine batch as {@code /management/batches/{id}/batch-parts} serialises it (7.x,
 * a bare array) — typed (#474). For a case-migration batch there is one part per case:
 * {@code scopeId} is the case instance id, {@code status} its outcome.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchPartRecord(
        String id, String batchId, String scopeId, String scopeType, String status, String completeTime) {}
