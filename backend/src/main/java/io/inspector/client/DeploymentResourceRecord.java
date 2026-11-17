package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code /repository/deployments/{id}/resources} (#474). The engine names a resource by
 * its {@code id}; {@code name} is kept for a payload that carries it instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeploymentResourceRecord(String id, String name, String mediaType, String type) {}
