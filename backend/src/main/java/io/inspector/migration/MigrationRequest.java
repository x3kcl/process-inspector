package io.inspector.migration;

import java.util.List;

/**
 * The operator's migration intent — SEMANTIC inputs only (P0 re-lock decision P0-4): a
 * target selector plus optional targeted mappings for activities the static pre-check flags
 * as unmappable. The BFF recomputes the static diff and REBUILDS the wire migration document
 * server-side; it never accepts a client-baked document, so a crafted body cannot diverge
 * from what the operator was shown.
 *
 * <p>Target selection (resolved to a concrete, pinned {@code toProcessDefinitionId}):
 * {@code toDefinitionId} wins if present; else {@code toVersion} of the instance's own key;
 * else the latest deployed version of the key.
 *
 * <p>{@code reason}/{@code ticketId}/{@code confirmToken} are unused by preview and consumed
 * by execute (reason ≥10 always; typed business-key {@code confirmToken} on prod).
 *
 * <p>{@code expectedFromDefinitionId}/{@code expectedActivityStateDigest} are the §5
 * compare-and-set assertions (decision P0-4): the frontend echoes back what the preview
 * returned. Both are MANDATORY for execute (400 {@code preview-required} if absent) so an
 * operator can never migrate under a stale approval; execute refuses 409 if either diverges from
 * the live state. Unused by preview.
 */
public record MigrationRequest(
        String toDefinitionId,
        Integer toVersion,
        List<MigrationMapping> mappings,
        String reason,
        String ticketId,
        String confirmToken,
        String expectedFromDefinitionId,
        String expectedActivityStateDigest) {

    public List<MigrationMapping> mappingsOrEmpty() {
        return mappings != null ? mappings : List.of();
    }
}
