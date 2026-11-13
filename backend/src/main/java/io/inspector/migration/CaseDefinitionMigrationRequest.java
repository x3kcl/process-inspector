package io.inspector.migration;

/**
 * Body of the three Flowable 7.x case-migration doors (#500). {@code confirmedCount} is the typed
 * count of the by-definition doors on a prod engine (running cases for batch, ended cases for
 * historic); {@code confirmToken} is the typed case id of the single historic-case door on prod.
 */
public record CaseDefinitionMigrationRequest(
        String toCaseDefinitionId, String reason, String ticketId, Integer confirmedCount, String confirmToken) {}
