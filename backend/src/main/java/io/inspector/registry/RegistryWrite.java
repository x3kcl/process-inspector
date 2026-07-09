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
        String authType, // basic | bearer | none
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
        Integer alarmOverdueGraceS) {}
