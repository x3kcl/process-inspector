package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.inspector.security.OidcGroupResolver;
import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.security.mapping.MappingSource;
import io.inspector.support.NoDbTestSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * #772: the metrics-bearing actuator endpoints ({@code /actuator/prometheus}, {@code /actuator/info})
 * need the METRICS fleet grant or a fleet-wide ADMIN, on the dev ({@code !oidc}) chain and for an OIDC
 * session token. Health, liveness and readiness keep answering anyone; an unauthenticated scrape still
 * gets 401. The groups of an OIDC token go through the real {@code RbacAuthorizer}; only the resolver
 * and the mapping it reads are stubbed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MetricsGrantActuatorSpringTest {

    private static final String UNMAPPED = "unmapped-user";
    private static final String SCOPED_ADMIN = "probe-dev-admin";
    private static final String FLEET_ADMIN = "fleet-admin";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OidcGroupResolver groups;

    @MockitoBean
    MappingSource mapping;

    @BeforeEach
    void mapping() {
        when(groups.resolveForCheck(anyMap(), any(), eq(UNMAPPED))).thenReturn(List.of("no-such-team"));
        when(groups.resolveForCheck(anyMap(), any(), eq(SCOPED_ADMIN))).thenReturn(List.of("team-dev"));
        when(groups.resolveForCheck(anyMap(), any(), eq(FLEET_ADMIN))).thenReturn(List.of("team-fleet"));
        when(mapping.grantsForGroups(List.of("team-dev")))
                .thenReturn(Set.of(new ScopeGrant(Role.ADMIN, "probe-dev", ScopeGrant.ANY)));
        when(mapping.grantsForGroups(List.of("team-fleet"))).thenReturn(Set.of(ScopeGrant.global(Role.ADMIN)));
    }

    private int status(String path, RequestPostProcessor who) throws Exception {
        var request = get(path);
        if (who != null) {
            request.with(who);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static RequestPostProcessor noRole() {
        return authentication(new UsernamePasswordAuthenticationToken("scraper", "n/a", List.of()));
    }

    private static RequestPostProcessor withAuthority(String authority) {
        return authentication(new UsernamePasswordAuthenticationToken(
                "scraper", "n/a", List.of(new SimpleGrantedAuthority(authority))));
    }

    private static RequestPostProcessor oidc(String subject) {
        return oidcLogin().idToken(token -> token.subject(subject));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void aSignedInPrincipalWithNoRoleIsRefused(String path) throws Exception {
        assertThat(status(path, noRole())).as(path).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void anOidcUserWhoseGroupsMapToNothingIsRefused(String path) throws Exception {
        assertThat(status(path, oidc(UNMAPPED))).as(path).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void aLadderViewerIsRefused(String path) throws Exception {
        assertThat(status(path, httpBasic("viewer", "dev"))).as(path).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void anAdminOnOneEngineOnlyIsRefused(String path) throws Exception {
        assertThat(status(path, oidc(SCOPED_ADMIN))).as(path).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void theMetricsGrantReads(String path) throws Exception {
        assertThat(status(path, withAuthority("ROLE_METRICS"))).as(path).isEqualTo(200);
        assertThat(status(path, httpBasic("metrics", "dev"))).as(path).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void aFleetWideAdminReads(String path) throws Exception {
        assertThat(status(path, httpBasic("admin", "dev"))).as(path).isEqualTo(200);
        assertThat(status(path, oidc(FLEET_ADMIN))).as(path).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void anUnauthenticatedScrapeStillGets401(String path) throws Exception {
        assertThat(status(path, null)).as(path).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthProbesAreUnchanged(String path) throws Exception {
        assertThat(status(path, null)).as(path).isEqualTo(200);
        assertThat(status(path, noRole())).as(path).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator"})
    void theDiscoveryRootIsUnchanged(String path) throws Exception {
        assertThat(status(path, null)).as(path).isEqualTo(401);
        assertThat(status(path, noRole())).as(path).isEqualTo(200);
    }
}
