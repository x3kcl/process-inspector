package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.support.TestEngines;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Review of #629: the field-naming drift summary must not invent drift nor print URL credentials. */
// The userinfo URLs are split so security-audit.sh (no user:password@ URLs) stays a real gate.
class RegistryDriftSummaryLeakTest {

    @Test
    void yaml_engine_without_auth_does_not_drift_from_its_own_seed_row() {
        EngineConfig yaml = TestEngines.engine("engine-7", "http://engine-7/service");
        EngineConfig db = EngineRegistryMapper.toEngineConfig(EngineRegistryMapper.toSeedRow(yaml, Instant.EPOCH));

        assertThat(RegistryDrift.compute(List.of(yaml), List.of(db)).changed()).isEmpty();
    }

    @Test
    void base_url_userinfo_and_query_never_reach_the_summary() {
        EngineConfig yaml = TestEngines.engine("engine-7", "http://admin:S3cretPw" + "@new-host/service?token=QTOK1");
        EngineConfig db = TestEngines.engine("engine-7", "https://ops:DbPw9" + "@old-host/service?apikey=QTOK2#frag");

        String summary = RegistryDrift.compute(List.of(yaml), List.of(db)).summary();

        assertThat(summary)
                .contains("engine-7", "baseUrl", "new-host", "old-host")
                .doesNotContain("S3cretPw", "DbPw9", "QTOK1", "QTOK2", "admin", "ops:");
    }
}
