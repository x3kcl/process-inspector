package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One engine batch as the process API's {@code /management/batches} serialises it (7.x) — the
 * typed wire shape of the case-migration batch read (#474). For a {@code caseMigration} batch
 * {@code searchKey} is the source case definition id and {@code searchKey2} the target.
 * {@code completeTime} is null while the batch runs. Times stay the engine's verbatim strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchRecord(
        String id,
        String batchType,
        String searchKey,
        String searchKey2,
        String createTime,
        String completeTime,
        String status,
        String tenantId) {}
