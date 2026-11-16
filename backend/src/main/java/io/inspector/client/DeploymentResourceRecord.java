package io.inspector.client;

/** One resource of a deployment as the resources endpoint serialises it (#474). */
public record DeploymentResourceRecord(String id, String name, String mediaType, String type) {}
