package io.inspector.client;

/** One engine batch (#474). */
public record BatchRecord(
        String id,
        String batchType,
        String searchKey,
        String searchKey2,
        String createTime,
        String completeTime,
        String status,
        String tenantId) {}
