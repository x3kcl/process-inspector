package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One deployment as {@code /repository/deployments} (list and by id) serialises it — the typed wire
 * shape of the deployment endpoint family (#474). {@code deploymentTime} stays the engine's
 * verbatim string.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeploymentRecord(String id, String name, String category, String tenantId, String deploymentTime) {}
