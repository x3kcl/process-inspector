package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.config.InspectorProperties.EngineEnvironment;
import org.junit.jupiter.api.Test;

class AuthTypesTest {

    @Test
    void forbidsHopByHopAndCredentialHeaderNames() {
        assertThatThrownBy(() -> AuthTypes.checkHeaderName("Authorization"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> AuthTypes.checkHeaderName("Cookie")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthTypes.checkHeaderName("Host")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> AuthTypes.checkHeaderName("X-Api-Key")).doesNotThrowAnyException();
    }

    @Test
    void oauthRequiresTokenUrlClientIdAndSecretRef() {
        assertThatThrownBy(() -> AuthTypes.check(write("oauth2-client-credentials", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth token url");
    }

    @Test
    void customHeaderRequiresANameAndASecretRef() {
        assertThatThrownBy(() -> AuthTypes.check(write("custom-header", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth header name");
    }

    private static RegistryWrite write(String type, String url, String clientId, String secretRef) {
        return new RegistryWrite(
                "e1",
                "Name",
                "https://engine.example/service",
                EngineEnvironment.TEST,
                null,
                null,
                null,
                type,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                url,
                clientId,
                secretRef,
                null,
                null,
                type.equals("custom-header") ? null : null,
                null);
    }
}
