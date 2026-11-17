package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.inspector.support.NoDbTestSupport;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * #772 on the REAL {@code oidc} chain (application-oidc.yml loaded, the oauth2Login chain built): the
 * metrics rule sits in the shared part of both chains, so a matcher that only reached the dev chain
 * would leave this one open. No IdP is contacted — the client registration is a static stub and the
 * session is minted by {@code oidcLogin()}; groups resolve through the real file mapping source with
 * the issuer pinned.
 */
@SpringBootTest(
        properties = {
            "INSPECTOR_OIDC_CLIENT_ID=pi-test",
            "INSPECTOR_OIDC_CLIENT_SECRET=unused",
            "INSPECTOR_OIDC_ISSUER_URI=" + MetricsGrantOidcChainSpringTest.ISSUER,
            "inspector.security.mapping.access-admin-group=pi-access-admins",
            "inspector.security.metrics-group=pi-metrics"
        })
@ActiveProfiles({"test", "oidc"})
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MetricsGrantOidcChainSpringTest {

    static final String ISSUER = "https://idp.invalid/realms/pi";

    @TestConfiguration
    static class StubRegistration {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("oidc")
                    .clientId("pi-test")
                    .clientSecret("unused")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri(ISSUER + "/auth")
                    .tokenUri(ISSUER + "/token")
                    .jwkSetUri(ISSUER + "/certs")
                    .issuerUri(ISSUER)
                    .build());
        }
    }

    @Autowired
    MockMvc mvc;

    private int status(String path, RequestPostProcessor who) throws Exception {
        var request = get(path);
        if (who != null) {
            request.with(who);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static RequestPostProcessor withGroups(String... groups) {
        return oidcLogin()
                .idToken(token -> token.subject("scraper").issuer(ISSUER).claim("groups", List.of(groups)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void aUserWhoseGroupsMapToNothingIsRefused(String path) throws Exception {
        assertThat(status(path, withGroups("no-such-team"))).as(path).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void theMetricsGroupReads(String path) throws Exception {
        assertThat(status(path, withGroups("pi-metrics"))).as(path).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void anotherFleetGrantDoesNotRead(String path) throws Exception {
        assertThat(status(path, withGroups("pi-access-admins"))).as(path).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthProbesAreUnchanged(String path) throws Exception {
        assertThat(status(path, null)).as(path).isEqualTo(200);
        assertThat(status(path, withGroups("no-such-team"))).as(path).isEqualTo(200);
    }
}
