package io.inspector.registry;

import io.inspector.config.InspectorProperties.EngineEnvironment;

/**
 * A validated registry write payload (add/edit) — the config an admin submits, secrets by REF name
 * only (docs/REGISTRY-CRUD.md §9). {@code id} is set on add and IGNORED on edit (immutable). The
 * base-URL is SSRF-validated by {@link EngineRegistryStore} before persistence; the do-no-harm
 * knobs are nullable → code defaults. Never carries a secret VALUE.
 */
public record RegistryWrite(
        String id,
        String name,
        String baseUrl,
        EngineEnvironment environment,
        String accentColor,
        String tenantId,
        String telemetryUrlTemplate,
        String authType, // basic | bearer | none | oauth2-client-credentials | custom-header
        String authUsername,
        String passwordRef,
        String tokenRef,
        Integer connectMs,
        Integer readMs,
        Integer writeMs,
        Integer maxPageSize,
        Integer dlqScanCap,
        Integer alarmOldestWarnMin,
        Integer alarmOldestCritMin,
        Integer alarmOverdueGraceS,
        String oauthTokenUrl,
        String oauthClientId,
        String oauthClientSecretRef,
        String oauthScope,
        String oauthAudience,
        String headerName,
        String headerSecretRef) {

    /** Pre-#490 write. OAuth and custom-header fields stay null. */
    public RegistryWrite(
            String id,
            String name,
            String baseUrl,
            EngineEnvironment environment,
            String accentColor,
            String tenantId,
            String telemetryUrlTemplate,
            String authType,
            String authUsername,
            String passwordRef,
            String tokenRef,
            Integer connectMs,
            Integer readMs,
            Integer writeMs,
            Integer maxPageSize,
            Integer dlqScanCap,
            Integer alarmOldestWarnMin,
            Integer alarmOldestCritMin,
            Integer alarmOverdueGraceS) {
        this(
                id,
                name,
                baseUrl,
                environment,
                accentColor,
                tenantId,
                telemetryUrlTemplate,
                authType,
                authUsername,
                passwordRef,
                tokenRef,
                connectMs,
                readMs,
                writeMs,
                maxPageSize,
                dlqScanCap,
                alarmOldestWarnMin,
                alarmOldestCritMin,
                alarmOverdueGraceS,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
