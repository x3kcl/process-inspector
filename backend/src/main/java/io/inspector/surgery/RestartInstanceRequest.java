package io.inspector.surgery;

/**
 * Restart-as-new (SPEC §5 tier 2): re-launch a COMPLETED/TERMINATED instance with its
 * historic variables copied onto a brand-new instance id. The version fork is explicit
 * (never a silent default the operator didn't choose): {@code pinDefinitionVersion=true}
 * starts on the original {@code processDefinitionId}; {@code false} (or absent) starts on
 * the definition KEY, which resolves to the latest deployed version.
 */
public record RestartInstanceRequest(String reason, String ticketId, Boolean pinDefinitionVersion) {}
