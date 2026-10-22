package io.inspector.catalog;

/**
 * Tier-3 delete-deployment body. {@code cascade} defaults to false: a deployment that still
 * has running instances is refused unless the operator sets this explicitly. On a prod engine
 * {@code confirmToken} must equal the deployment id.
 */
public record DeleteDeploymentRequest(String reason, String ticketId, String confirmToken, Boolean cascade) {}
