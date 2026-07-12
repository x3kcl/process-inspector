package io.inspector.support;

import io.inspector.audit.AuditPayloadMode;
import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;

/**
 * Test-support factories for {@link EngineConfig} — new record fields land HERE, not in
 * twenty constructor call sites (unit-test-patterns: constructor-churn trap).
 *
 * <p>Test-support consolidation (F5, issue #90): the named factories below cover the common
 * shapes and stay unchanged for their ~60 existing call sites; {@link #builder(String, String)}
 * is the escape hatch for a fixture needing a field none of them expose (lifecycle,
 * deepPagingMaxDepth, maxPageSize, dlqScanCap, alarmThresholds, telemetryUrlTemplate,
 * auditPayload, accentColor, a non-default name, or {@code enabled=false}) — a fluent builder
 * with named setters instead of one more positional overload, so a NEW {@code EngineConfig}
 * field never means a NEW {@code TestEngines} method.
 */
public final class TestEngines {

    private TestEngines() {}

    public static EngineConfig engine(String id, String baseUrl) {
        return builder(id, baseUrl).build();
    }

    public static EngineConfig engine(String id, String baseUrl, Auth auth, Timeouts timeouts) {
        return builder(id, baseUrl).auth(auth).timeouts(timeouts).build();
    }

    /** Tenant-pinned fixture (S2 read-scope tests): the engine's registry-pinned {@code tenantId}. */
    public static EngineConfig engineInTenant(String id, String baseUrl, String tenantId) {
        return builder(id, baseUrl).tenantId(tenantId).build();
    }

    /** Guard-ladder fixtures (M4): environment drives reason/token strictness, mode the R-GOV-04 gate. */
    public static EngineConfig engine(String id, String baseUrl, EngineEnvironment environment, EngineMode mode) {
        return builder(id, baseUrl).environment(environment).mode(mode).build();
    }

    /** X-Forwarded-User send-side fixture (M4-CLOSEOUT §2 / S4): forward-user ON. */
    public static EngineConfig forwardUserEngine(String id, String baseUrl, Auth auth, Timeouts timeouts) {
        return builder(id, baseUrl)
                .auth(auth)
                .timeouts(timeouts)
                .forwardUser(true)
                .build();
    }

    public static Auth basicAuth(String username, String passwordRef) {
        return new Auth(Auth.Type.basic, username, passwordRef, null);
    }

    public static Builder builder(String id, String baseUrl) {
        return new Builder(id, baseUrl);
    }

    /** Same defaults as the named factories above: {@code name=id}, DEV, enabled, everything else null/off. */
    public static final class Builder {
        private final String id;
        private final String baseUrl;
        private String name;
        private EngineEnvironment environment = EngineEnvironment.DEV;
        private String accentColor;
        private boolean enabled = true;
        private String tenantId;
        private String telemetryUrlTemplate;
        private Auth auth;
        private EngineMode mode;
        private Timeouts timeouts;
        private Integer maxPageSize;
        private Integer dlqScanCap;
        private AlarmThresholds alarmThresholds;
        private AuditPayloadMode auditPayload;
        private boolean forwardUser;
        private Integer deepPagingMaxDepth;
        private String lifecycle;

        private Builder(String id, String baseUrl) {
            this.id = id;
            this.baseUrl = baseUrl;
            this.name = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder environment(EngineEnvironment environment) {
            this.environment = environment;
            return this;
        }

        public Builder accentColor(String accentColor) {
            this.accentColor = accentColor;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder telemetryUrlTemplate(String telemetryUrlTemplate) {
            this.telemetryUrlTemplate = telemetryUrlTemplate;
            return this;
        }

        public Builder auth(Auth auth) {
            this.auth = auth;
            return this;
        }

        public Builder mode(EngineMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder timeouts(Timeouts timeouts) {
            this.timeouts = timeouts;
            return this;
        }

        public Builder maxPageSize(Integer maxPageSize) {
            this.maxPageSize = maxPageSize;
            return this;
        }

        public Builder dlqScanCap(Integer dlqScanCap) {
            this.dlqScanCap = dlqScanCap;
            return this;
        }

        public Builder alarmThresholds(AlarmThresholds alarmThresholds) {
            this.alarmThresholds = alarmThresholds;
            return this;
        }

        public Builder auditPayload(AuditPayloadMode auditPayload) {
            this.auditPayload = auditPayload;
            return this;
        }

        public Builder forwardUser(boolean forwardUser) {
            this.forwardUser = forwardUser;
            return this;
        }

        public Builder deepPagingMaxDepth(Integer deepPagingMaxDepth) {
            this.deepPagingMaxDepth = deepPagingMaxDepth;
            return this;
        }

        public Builder lifecycle(String lifecycle) {
            this.lifecycle = lifecycle;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(
                    id,
                    name,
                    baseUrl,
                    environment,
                    accentColor,
                    enabled,
                    tenantId,
                    telemetryUrlTemplate,
                    auth,
                    mode,
                    timeouts,
                    maxPageSize,
                    dlqScanCap,
                    alarmThresholds,
                    auditPayload,
                    forwardUser,
                    deepPagingMaxDepth,
                    lifecycle);
        }
    }
}
