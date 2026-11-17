package io.inspector.client;

import java.util.Objects;

/**
 * The body of every CMMN migration POST ({@link CaseMigrationRoute}, #720): the target case
 * definition and nothing else. A null target is refused here, so it is never sent as
 * {@code null}.
 */
public record CaseMigrationDocument(String toCaseDefinitionId) {

    public CaseMigrationDocument {
        Objects.requireNonNull(toCaseDefinitionId, "toCaseDefinitionId");
    }
}
