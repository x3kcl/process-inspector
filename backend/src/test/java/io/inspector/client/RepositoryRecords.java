package io.inspector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * Test fixture: build typed process-definition and deployment rows from the wire-keyed maps the
 * tests already read as JSON.
 */
public final class RepositoryRecords {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RepositoryRecords() {}

    public static ProcessDefinitionRecord definition(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, ProcessDefinitionRecord.class);
    }

    public static List<ProcessDefinitionRecord> definitions(List<? extends Map<String, ?>> wire) {
        return wire.stream().map(RepositoryRecords::definition).toList();
    }

    public static ProcessDefinitionPage definitionPage(List<? extends Map<String, ?>> wire) {
        return definitionPage(wire, wire.size());
    }

    public static ProcessDefinitionPage definitionPage(List<? extends Map<String, ?>> wire, long total) {
        return new ProcessDefinitionPage(definitions(wire), total, 0, wire.size());
    }

    public static DeploymentRecord deployment(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, DeploymentRecord.class);
    }

    public static DeploymentPage deploymentPage(List<? extends Map<String, ?>> wire, long total) {
        return new DeploymentPage(
                wire.stream().map(RepositoryRecords::deployment).toList(), total, 0, wire.size());
    }

    public static List<DeploymentResourceRecord> resources(List<? extends Map<String, ?>> wire) {
        return wire.stream()
                .map(row -> MAPPER.convertValue(row, DeploymentResourceRecord.class))
                .toList();
    }
}
