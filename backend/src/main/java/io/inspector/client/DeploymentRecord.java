package io.inspector.client;

/** One deployment as the repository endpoints serialise it (#474). */
public record DeploymentRecord(String id, String name, String category, String tenantId, String deploymentTime) {}
