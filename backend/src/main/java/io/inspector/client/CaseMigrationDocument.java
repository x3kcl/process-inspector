package io.inspector.client;

/** The body of every CMMN migration POST ({@link CaseMigrationRoute}, #720). */
public record CaseMigrationDocument(String toCaseDefinitionId) {}
