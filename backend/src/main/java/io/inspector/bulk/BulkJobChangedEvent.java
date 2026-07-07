package io.inspector.bulk;

import java.util.UUID;

/**
 * Id-only live signal (v1.x #2, SPEC §7): something about this bulk job changed — submit,
 * item settled, terminal state. Carries NO payload beyond the id: subscribers (the SSE
 * bridge) push the id and every browser refetches its own JSON, never a fanned-out body.
 */
public record BulkJobChangedEvent(UUID jobId) {}
