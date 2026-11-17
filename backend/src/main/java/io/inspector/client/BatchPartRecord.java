package io.inspector.client;

/** One part of an engine batch (#474). */
public record BatchPartRecord(
        String id, String batchId, String scopeId, String scopeType, String status, String completeTime) {}
