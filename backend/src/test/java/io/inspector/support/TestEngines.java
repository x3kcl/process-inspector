package io.inspector.support;

import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;

/**
 * Test-support factories for {@link EngineConfig} — new record fields land HERE, not in
 * twenty constructor call sites (unit-test-patterns: constructor-churn trap).
 */
public final class TestEngines {

    private TestEngines() {}

    public static EngineConfig engine(String id, String baseUrl) {
        return engine(id, baseUrl, (Auth) null, null);
    }

    public static EngineConfig engine(String id, String baseUrl, Auth auth, Timeouts timeouts) {
        return new EngineConfig(
                id, id, baseUrl, EngineEnvironment.DEV, null, true, null, auth, null, timeouts, null, null, null);
    }

    /** Guard-ladder fixtures (M4): environment drives reason/token strictness, mode the R-GOV-04 gate. */
    public static EngineConfig engine(String id, String baseUrl, EngineEnvironment environment, EngineMode mode) {
        return new EngineConfig(id, id, baseUrl, environment, null, true, null, null, mode, null, null, null, null);
    }

    public static Auth basicAuth(String username, String passwordRef) {
        return new Auth(Auth.Type.basic, username, passwordRef, null);
    }
}
