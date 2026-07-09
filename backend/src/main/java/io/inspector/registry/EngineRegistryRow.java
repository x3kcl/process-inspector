package io.inspector.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One engine registry row (V7 {@code engine_registry}) — the DB-authoritative form of what used
 * to be a single {@code inspector.engines} YAML entry (docs/REGISTRY-CRUD.md §10). Schema is the
 * truth, {@code ddl-auto=validate}. Not per-user owned — global/fleet, gated by REGISTRY_ADMIN.
 *
 * <p>Deliberately a flat, string-keyed data holder: {@code id} is the immutable slug PK, the enum
 * columns ({@code environment}/{@code mode}/{@code lifecycle}/{@code auth_type}/{@code source}) are
 * stored as their DB text values and converted at the seam by {@link EngineRegistryMapper}. Secrets
 * are env-var NAMES only ({@code passwordRef}/{@code tokenRef}) — never a value (iron rule).
 */
@Entity
@Table(name = "engine_registry")
public class EngineRegistryRow {

    @Id
    @Column(name = "id", updatable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "environment", nullable = false)
    private String environment;

    @Column(name = "mode", nullable = false)
    private String mode;

    @Column(name = "lifecycle", nullable = false)
    private String lifecycle;

    @Column(name = "accent_color")
    private String accentColor;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "telemetry_url_template")
    private String telemetryUrlTemplate;

    @Column(name = "auth_type", nullable = false)
    private String authType;

    @Column(name = "auth_username")
    private String authUsername;

    @Column(name = "password_ref")
    private String passwordRef;

    @Column(name = "token_ref")
    private String tokenRef;

    @Column(name = "connect_ms")
    private Integer connectMs;

    @Column(name = "read_ms")
    private Integer readMs;

    @Column(name = "write_ms")
    private Integer writeMs;

    @Column(name = "max_page_size")
    private Integer maxPageSize;

    @Column(name = "dlq_scan_cap")
    private Integer dlqScanCap;

    @Column(name = "alarm_oldest_warn_min")
    private Integer alarmOldestWarnMin;

    @Column(name = "alarm_oldest_crit_min")
    private Integer alarmOldestCritMin;

    @Column(name = "alarm_overdue_grace_s")
    private Integer alarmOverdueGraceS;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "source", nullable = false)
    private String source;

    protected EngineRegistryRow() {
        // JPA
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(String lifecycle) {
        this.lifecycle = lifecycle;
    }

    public String getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(String accentColor) {
        this.accentColor = accentColor;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTelemetryUrlTemplate() {
        return telemetryUrlTemplate;
    }

    public void setTelemetryUrlTemplate(String telemetryUrlTemplate) {
        this.telemetryUrlTemplate = telemetryUrlTemplate;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getAuthUsername() {
        return authUsername;
    }

    public void setAuthUsername(String authUsername) {
        this.authUsername = authUsername;
    }

    public String getPasswordRef() {
        return passwordRef;
    }

    public void setPasswordRef(String passwordRef) {
        this.passwordRef = passwordRef;
    }

    public String getTokenRef() {
        return tokenRef;
    }

    public void setTokenRef(String tokenRef) {
        this.tokenRef = tokenRef;
    }

    public Integer getConnectMs() {
        return connectMs;
    }

    public void setConnectMs(Integer connectMs) {
        this.connectMs = connectMs;
    }

    public Integer getReadMs() {
        return readMs;
    }

    public void setReadMs(Integer readMs) {
        this.readMs = readMs;
    }

    public Integer getWriteMs() {
        return writeMs;
    }

    public void setWriteMs(Integer writeMs) {
        this.writeMs = writeMs;
    }

    public Integer getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(Integer maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public Integer getDlqScanCap() {
        return dlqScanCap;
    }

    public void setDlqScanCap(Integer dlqScanCap) {
        this.dlqScanCap = dlqScanCap;
    }

    public Integer getAlarmOldestWarnMin() {
        return alarmOldestWarnMin;
    }

    public void setAlarmOldestWarnMin(Integer alarmOldestWarnMin) {
        this.alarmOldestWarnMin = alarmOldestWarnMin;
    }

    public Integer getAlarmOldestCritMin() {
        return alarmOldestCritMin;
    }

    public void setAlarmOldestCritMin(Integer alarmOldestCritMin) {
        this.alarmOldestCritMin = alarmOldestCritMin;
    }

    public Integer getAlarmOverdueGraceS() {
        return alarmOverdueGraceS;
    }

    public void setAlarmOverdueGraceS(Integer alarmOverdueGraceS) {
        this.alarmOverdueGraceS = alarmOverdueGraceS;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public void setRemovedAt(Instant removedAt) {
        this.removedAt = removedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
