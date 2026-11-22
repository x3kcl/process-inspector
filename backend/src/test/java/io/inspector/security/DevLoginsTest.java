package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: who signs in with which password on the dev ladder. Public users take the public
 * password ONLY, every other user the private one ONLY; nothing set = today's single password.
 */
class DevLoginsTest {

    private static final List<String> USERS = List.of("viewer", "operator", "admin", "registry-admin");

    @Test
    void withoutAPublicPasswordEveryUserKeepsThePrivateOne() {
        // Given no public settings, When the ladder is built, Then every user has the private password.
        assertThat(DevLogins.passwords(USERS, "private-1", null, null))
                .isEqualTo(Map.of(
                        "viewer",
                        "private-1",
                        "operator",
                        "private-1",
                        "admin",
                        "private-1",
                        "registry-admin",
                        "private-1"));
        assertThat(DevLogins.passwords(USERS, "private-1", "", List.of()))
                .containsOnlyKeys(USERS)
                .allSatisfy((user, password) -> assertThat(password).isEqualTo("private-1"));
    }

    @Test
    void withNothingSetTheDefaultStaysDev() {
        assertThat(DevLogins.passwords(USERS, null, null, null).values()).containsOnly("dev");
        assertThat(DevLogins.passwords(USERS, "  ", null, null).values()).containsOnly("dev");
    }

    @Test
    void listedUsersTakeThePublicPasswordAndTheRestThePrivateOne() {
        assertThat(DevLogins.passwords(USERS, "private-1", "public-1", List.of("viewer", "operator")))
                .isEqualTo(Map.of(
                        "viewer",
                        "public-1",
                        "operator",
                        "public-1",
                        "admin",
                        "private-1",
                        "registry-admin",
                        "private-1"));
    }

    @Test
    void userEntriesAreTrimmedAndBlankEntriesIgnored() {
        // An env comma list ("viewer, ,operator ") must not produce a phantom or mangled name.
        assertThat(DevLogins.passwords(
                        USERS, "private-1", "public-1", Arrays.asList(" viewer", " ", null, "operator ")))
                .containsEntry("viewer", "public-1")
                .containsEntry("operator", "public-1")
                .containsEntry("admin", "private-1")
                .hasSize(4);
    }

    @Test
    void aPublicPasswordEqualToThePrivateOneIsRefused() {
        assertThatThrownBy(() -> DevLogins.passwords(USERS, "same-1", "same-1", List.of("viewer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void aPublicPasswordWithoutAnExplicitPrivatePasswordIsRefused() {
        // Otherwise every non-public user would fall back to the well-known default "dev".
        assertThatThrownBy(() -> DevLogins.passwords(USERS, null, "public-1", List.of("viewer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit inspector.security.dev-password");
        assertThatThrownBy(() -> DevLogins.passwords(USERS, "", "public-1", List.of("viewer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit inspector.security.dev-password");
    }

    @Test
    void halfAConfigurationIsRefused() {
        assertThatThrownBy(() -> DevLogins.passwords(USERS, "private-1", "public-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("set together");
        assertThatThrownBy(() -> DevLogins.passwords(USERS, "private-1", "public-1", List.of(" ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("set together");
        assertThatThrownBy(() -> DevLogins.passwords(USERS, "private-1", " ", List.of("viewer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("set together");
    }

    @Test
    void anUnknownPublicUserIsRefused() {
        assertThatThrownBy(() -> DevLogins.passwords(USERS, "private-1", "public-1", List.of("viewer", "guest")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown user: guest");
    }

    @Test
    void theEncoderComparesWholeValuesOnly() {
        assertThat(DevLogins.ENCODER.encode("public-1")).isEqualTo("public-1");
        assertThat(DevLogins.ENCODER.matches("public-1", "public-1")).isTrue();
        assertThat(DevLogins.ENCODER.matches("public-2", "public-1")).isFalse();
        assertThat(DevLogins.ENCODER.matches("public-", "public-1")).isFalse();
        assertThat(DevLogins.ENCODER.matches("public-11", "public-1")).isFalse();
        assertThat(DevLogins.ENCODER.matches("", "public-1")).isFalse();
        assertThat(DevLogins.ENCODER.matches(null, "public-1")).isFalse();
        assertThat(DevLogins.ENCODER.matches("public-1", null)).isFalse();
    }
}
