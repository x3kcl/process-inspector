package io.inspector.migration;

import io.inspector.client.CaseMigrationDocument;
import io.inspector.client.CaseMigrationRoute;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.List;
import java.util.Map;

/**
 * How the migration characterization ({@code MigrationBodyWireShapeTest}, #720) reaches the two
 * client calls whose body parameter changes type, and the map copy of the document the preview
 * and the audit row carry. Kept out of the frozen RED file so the test states inputs and expected
 * bytes only. The engine calls take the typed documents; in the RED run this adapter sent
 * the maps {@code main} built (the insertion-ordered document, the one-key {@code Map.of}).
 */
final class MigrationWire {

    private MigrationWire() {}

    static void migrate(
            ProcessApiClient client,
            EngineConfig engine,
            String processInstanceId,
            String toProcessDefinitionId,
            List<MigrationMapping> overrides) {
        client.migrateInstance(
                engine,
                CallPriority.INTERACTIVE,
                processInstanceId,
                MigrationMapping.document(toProcessDefinitionId, overrides));
    }

    /** The document as a map — what the preview DTO shows and the audit row stores. */
    static Map<String, Object> restBody(String toProcessDefinitionId, List<MigrationMapping> overrides) {
        return MigrationMapping.restBody(toProcessDefinitionId, overrides);
    }

    static void migrateCase(
            CmmnApiClient client,
            EngineConfig engine,
            String engineVersion,
            CaseMigrationRoute route,
            String id,
            String toCaseDefinitionId) {
        client.migrateCase(
                engine,
                CallPriority.INTERACTIVE,
                engineVersion,
                route,
                id,
                new CaseMigrationDocument(toCaseDefinitionId));
    }
}
