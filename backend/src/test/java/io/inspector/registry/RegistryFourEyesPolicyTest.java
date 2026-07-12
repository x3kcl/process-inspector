package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Rung 1: the registry four-eyes escalation matrix (R-SAFE-08, #91) — pure, CI-gating. */
class RegistryFourEyesPolicyTest {

    @Test
    void prod_read_write_enable_requires_four_eyes() {
        assertThat(RegistryFourEyesPolicy.requiresFourEyes(RegistryChange.Kind.ENABLE_READ_WRITE, "prod"))
                .isTrue();
    }

    @Test
    void non_prod_read_write_enable_is_single_actor() {
        assertThat(RegistryFourEyesPolicy.requiresFourEyes(RegistryChange.Kind.ENABLE_READ_WRITE, "dev"))
                .isFalse();
        assertThat(RegistryFourEyesPolicy.requiresFourEyes(RegistryChange.Kind.ENABLE_READ_WRITE, "test"))
                .isFalse();
        assertThat(RegistryFourEyesPolicy.requiresFourEyes(RegistryChange.Kind.ENABLE_READ_WRITE, null))
                .isFalse();
    }

    @Test
    void remove_and_purge_always_require_four_eyes_regardless_of_environment() {
        for (String env : new String[] {"prod", "dev", "test", null}) {
            assertThat(RegistryFourEyesPolicy.requiresFourEyes(RegistryChange.Kind.REMOVE, env))
                    .isTrue();
            assertThat(RegistryFourEyesPolicy.requiresFourEyes(RegistryChange.Kind.PURGE, env))
                    .isTrue();
        }
    }
}
