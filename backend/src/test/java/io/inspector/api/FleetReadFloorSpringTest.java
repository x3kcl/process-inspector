package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.security.OidcGroupResolver;
import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.security.mapping.MappingSource;
import io.inspector.support.NoDbTestSupport;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * #758 (R-SAFE-17, E7 of #739): the eleven fleet-wide reads require at least VIEWER on some engine
 * (or a fleet-wide role), with read-scope enforcement OFF (the default). A signed-in OIDC user whose
 * groups map to nothing — an unmapped user, exactly what an OIDC deployment produces when sign-in
 * does not imply a role — is refused with the named {@code no-engine-role} code; a user scoped to
 * ONE engine still reads the whole fleet; the dev global ladder and break-glass are unaffected.
 * The groups are resolved through the real {@code RbacAuthorizer}; only the two seams it reads
 * (the group resolver and the mapping) are stubbed, as an OIDC tenant would configure them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
class FleetReadFloorSpringTest {

    private static final String UNMAPPED = "unmapped-user";
    private static final String SCOPED = "probe-dev-viewer";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    OidcGroupResolver groups;

    @MockitoBean
    MappingSource mapping;

    @BeforeEach
    void mapping() {
        when(groups.resolveForCheck(anyMap(), any(), eq(UNMAPPED))).thenReturn(List.of("no-such-team"));
        when(groups.resolveForCheck(anyMap(), any(), eq(SCOPED))).thenReturn(List.of("team-dev"));
        when(mapping.grantsForGroups(List.of("team-dev")))
                .thenReturn(Set.of(new ScopeGrant(Role.VIEWER, "probe-dev", ScopeGrant.ANY)));
    }

    static Stream<String> fleetReads() {
        return Stream.of(
                "GET /api/definitions",
                "GET /api/deployments",
                "GET /api/engines",
                "GET /api/engines/probe-dev/system",
                "GET /api/jobs",
                "GET /api/tasks/browse",
                "GET /api/tasks?person=alice",
                "POST /api/search",
                "GET /api/triage",
                "GET /api/triage/trends",
                "GET /api/triage/leak-views");
    }

    private MockHttpServletResponse call(String route, RequestPostProcessor who) throws Exception {
        String[] parts = route.split(" ", 2);
        MockHttpServletRequestBuilder request = parts[0].equals("POST")
                ? post(parts[1])
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                : get(parts[1]);
        return mvc.perform(request.with(who)).andReturn().getResponse();
    }

    private static RequestPostProcessor oidc(String subject) {
        return oidcLogin().idToken(token -> token.subject(subject));
    }

    @ParameterizedTest
    @MethodSource("fleetReads")
    void aSignedInUserWithNoRoleIsRefusedWithANamedCode(String route) throws Exception {
        MockHttpServletResponse res = call(route, oidc(UNMAPPED));

        assertThat(res.getStatus()).as(route).isEqualTo(403);
        assertThat(mapper.readTree(res.getContentAsString()).path("code").asText())
                .as(route)
                .isEqualTo("no-engine-role");
    }

    @ParameterizedTest
    @MethodSource("fleetReads")
    void aViewerOnOneEngineStillReadsEveryFleetRoute(String route) throws Exception {
        assertThat(call(route, oidc(SCOPED)).getStatus()).as(route).isEqualTo(200);
    }

    @Test
    void aViewerOnOneEngineSeesTheWholeFleetWithEnforcementOff() throws Exception {
        MockHttpServletResponse res = call("GET /api/engines", oidc(SCOPED));

        JsonNode engines = mapper.readTree(res.getContentAsString());
        assertThat(engines.findValuesAsText("id")).containsExactlyInAnyOrder("probe-dev", "probe-prod");
    }

    @ParameterizedTest
    @MethodSource("fleetReads")
    void aFleetWideRoleIsUnaffected(String route) throws Exception {
        assertThat(call(route, httpBasic("viewer", "dev")).getStatus())
                .as(route)
                .isEqualTo(200);
    }

    @ParameterizedTest
    @MethodSource("fleetReads")
    void breakGlassIsUnaffected(String route) throws Exception {
        var breakGlass = new UsernamePasswordAuthenticationToken(
                "break-glass",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_BREAK_GLASS")));
        assertThat(call(route, authentication(breakGlass)).getStatus())
                .as(route)
                .isEqualTo(200);
    }

    @Test
    void aPerEngineRefusalOfARoleHolderIsNotTheNoRoleCode() throws Exception {
        MockHttpServletResponse res = call("GET /api/definitions/probe-prod/def-1", oidc(SCOPED));

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).doesNotContain("no-engine-role");
    }
}
