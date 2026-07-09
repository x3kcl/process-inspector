package io.inspector.registry;

import io.inspector.audit.AuditPayloadMode;
import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;
import java.time.Instant;
import java.util.Locale;

/**
 * The seam between a persisted {@link EngineRegistryRow} and the in-memory {@link EngineConfig}
 * every consumer already speaks (docs/REGISTRY-CRUD.md §10). Kept as a pure, static, well-tested
 * converter so the DB-authoritative path produces exactly the same {@code EngineConfig} shape the
 * YAML path does — the whole system updates for free when S3 flips {@code EngineRegistry} to read
 * the store.
 *
 * <p>Enum columns are stored as their DB text ({@code dev}/{@code read-write}/{@code active}/
 * {@code basic}); the {@code enabled} boolean projects the lifecycle ({@code active} ⇒ enabled).
 * Secrets remain env-var NAMES only.
 */
public final class EngineRegistryMapper {

    private EngineRegistryMapper() {}

    /** DB text values for {@code mode} / {@code lifecycle} / {@code source}. */
    static final String MODE_READ_WRITE = "read-write";

    static final String MODE_READ_ONLY = "read-only";
    static final String LIFECYCLE_ACTIVE = "active";
    static final String LIFECYCLE_DISABLED = "disabled";
    static final String SOURCE_YAML_SEED = "yaml-seed";
    static final String SOURCE_UI = "ui";

    /** A live row → the {@link EngineConfig} the fan-out / client already consume. */
    public static EngineConfig toEngineConfig(EngineRegistryRow row) {
        EngineEnvironment environment =
                EngineEnvironment.valueOf(row.getEnvironment().toUpperCase(Locale.ROOT));
        EngineMode mode = MODE_READ_ONLY.equals(row.getMode()) ? EngineMode.READ_ONLY : EngineMode.READ_WRITE;
        boolean enabled = LIFECYCLE_ACTIVE.equals(row.getLifecycle());

        Auth auth = new Auth(
                Auth.Type.valueOf(row.getAuthType()), row.getAuthUsername(), row.getPasswordRef(), row.getTokenRef());
        Timeouts timeouts = new Timeouts(row.getConnectMs(), row.getReadMs(), row.getWriteMs());
        AlarmThresholds alarms = new AlarmThresholds(
                row.getAlarmOldestWarnMin(), row.getAlarmOldestCritMin(), row.getAlarmOverdueGraceS());

        return new EngineConfig(
                row.getId(),
                row.getName(),
                row.getBaseUrl(),
                environment,
                row.getAccentColor(),
                enabled,
                row.getTenantId(),
                row.getTelemetryUrlTemplate(),
                auth,
                mode,
                timeouts,
                row.getMaxPageSize(),
                row.getDlqScanCap(),
                alarms,
                AuditPayloadMode.fromWire(row.getAuditPayload()),
                row.isForwardUser());
    }

    /**
     * An {@link EngineConfig} → a new row for the YAML seed import. Preserves the engine's current
     * enabled/mode faithfully: an enabled YAML engine lands {@code active} (it was already live),
     * a disabled one lands {@code disabled} — the seed is an honest snapshot, not a re-onboarding
     * through the DRAFT ramp.
     */
    public static EngineRegistryRow toSeedRow(EngineConfig engine, Instant now) {
        EngineRegistryRow row = new EngineRegistryRow();
        row.setId(engine.id());
        row.setName(engine.name());
        row.setBaseUrl(engine.baseUrl());
        row.setEnvironment(engine.environment().name().toLowerCase(Locale.ROOT));
        row.setMode(engine.modeOrDefault() == EngineMode.READ_ONLY ? MODE_READ_ONLY : MODE_READ_WRITE);
        row.setLifecycle(engine.enabled() ? LIFECYCLE_ACTIVE : LIFECYCLE_DISABLED);
        row.setAccentColor(engine.accentColor());
        row.setTenantId(engine.tenantId());
        row.setTelemetryUrlTemplate(engine.telemetryUrlTemplate());

        Auth auth = engine.auth();
        row.setAuthType(auth != null ? auth.type().name() : Auth.Type.none.name());
        if (auth != null) {
            row.setAuthUsername(auth.username());
            row.setPasswordRef(auth.passwordRef());
            row.setTokenRef(auth.tokenRef());
        }

        Timeouts timeouts = engine.timeouts();
        if (timeouts != null) {
            row.setConnectMs(timeouts.connectMs());
            row.setReadMs(timeouts.readMs());
            row.setWriteMs(timeouts.writeMs());
        }
        row.setMaxPageSize(engine.maxPageSize());
        row.setDlqScanCap(engine.dlqScanCap());

        AlarmThresholds alarms = engine.alarmThresholds();
        if (alarms != null) {
            row.setAlarmOldestWarnMin(alarms.oldestJobWarnMin());
            row.setAlarmOldestCritMin(alarms.oldestJobCritMin());
            row.setAlarmOverdueGraceS(alarms.overdueTimerGraceS());
        }

        row.setAuditPayload(engine.auditPayloadOrDefault().wire());
        row.setForwardUser(engine.forwardUser());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setSource(SOURCE_YAML_SEED);
        return row;
    }
}
