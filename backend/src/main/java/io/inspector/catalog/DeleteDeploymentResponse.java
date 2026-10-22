package io.inspector.catalog;

/** Outcome of a delete-deployment call. {@code outcome} is {@code ok}, {@code failed}, or {@code unknown}. */
public record DeleteDeploymentResponse(String outcome, String detail, long runningCount, boolean cascade) {}
