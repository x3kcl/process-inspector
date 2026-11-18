package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.inspector.security.OidcGroupResolver;
import io.inspector.security.mapping.FleetGrant;
import io.inspector.security.mapping.MappingSource;
import io.inspector.support.NoDbTestSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #772: the METRICS fleet grant resolved through the mapping seam for an OIDC session, and the
 * {@code metrics} flag on {@code GET /api/me}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MetricsGrantFleetSpringTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OidcGroupResolver groups;

    @MockitoBean
    MappingSource mapping;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info"})
    void anOidcGroupHoldingTheMetricsFleetGrantReads(String path) throws Exception {
        when(groups.resolveForCheck(anyMap(), any(), eq("scraper-sub"))).thenReturn(List.of("team-metrics"));
        when(mapping.fleetGrantsForGroups(List.of("team-metrics"))).thenReturn(Set.of(FleetGrant.METRICS));
        int status = mvc.perform(get(path).with(oidcLogin().idToken(t -> t.subject("scraper-sub"))))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status).as(path).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({"metrics,true", "admin,true", "viewer,false", "access-admin,false"})
    void meShowsTheMetricsGrant(String user, boolean expected) throws Exception {
        String body = mvc.perform(get("/api/me").with(httpBasic(user, "dev")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).as(user).contains("\"metrics\":" + expected);
    }
}
