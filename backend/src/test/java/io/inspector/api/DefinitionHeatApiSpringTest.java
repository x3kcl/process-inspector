package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.client.ActivityInstancePage;
import io.inspector.client.ActivityInstanceRecord;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessDefinitionRecord;
import io.inspector.client.ProcessInstancePage;
import io.inspector.client.ProcessInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.heat.DefinitionHeatService;
import io.inspector.security.OidcGroupResolver;
import io.inspector.security.ReadScopeGate;
import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.security.mapping.MappingSource;
import io.inspector.support.NoDbTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * #784 rung 3: {@code GET /api/definitions/{engineId}/{definitionId}/heat} over real Spring Security
 * and the real service + cache; only the engine client and the two identity seams are stubbed. RBAC
 * (VIEWER on THAT engine, the #758/#761 floor, read scope under enforcement), the 10-minute cache
 * with {@code cached}/{@code asOf}, the refresh throttle (once per 60 s per key, a refused refresh
 * answers the cached value plus {@code refreshThrottledUntil}), and single-flight. Each test uses
 * its own definition id: the cache is a singleton across the cached context.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
class DefinitionHeatApiSpringTest {

    private static final String UNMAPPED = "unmapped-user";
    private static final String SCOPED = "probe-dev-viewer";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    DefinitionHeatService service;

    @MockitoBean
    OidcGroupResolver groups;

    @MockitoBean
    MappingSource mapping;

    @MockitoBean
    ReadScopeGate gate;

    @MockitoBean
    ProcessApiClient flowable;

    private ProcessInstancePage samplePage;

    @BeforeEach
    void engineAnswersOneFinishedInstance() {
        when(groups.resolveForCheck(anyMap(), any(), eq(UNMAPPED))).thenReturn(List.of("no-such-team"));
        when(groups.resolveForCheck(anyMap(), any(), eq(SCOPED))).thenReturn(List.of("team-dev"));
        when(mapping.grantsForGroups(List.of("team-dev")))
                .thenReturn(Set.of(new ScopeGrant(Role.VIEWER, "probe-dev", ScopeGrant.ANY)));

        when(flowable.getProcessDefinition(any(), any(), anyString()))
                .thenAnswer(inv -> new ProcessDefinitionRecord(
                        inv.getArgument(2), "heatSeed", "Heat seed", 1, false, "dep", null, "", null, null, true));
        ProcessInstanceRecord instance = mock(ProcessInstanceRecord.class);
        when(instance.id()).thenReturn("p1");
        when(instance.endTime()).thenReturn("2026-11-17T10:00:02.000+00:00");
        samplePage = new ProcessInstancePage(List.of(instance), 7, 0, 1);
        when(flowable.queryHistoricProcessInstances(any(), any(), any())).thenReturn(samplePage);
        when(flowable.listHistoricActivities(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new ActivityInstancePage(
                        List.of(new ActivityInstanceRecord(
                                "a1",
                                "check",
                                "Check",
                                "manualTask",
                                "e1",
                                "p1",
                                "d",
                                null,
                                null,
                                null,
                                "2026-11-17T10:00:00.000+00:00",
                                "2026-11-17T10:00:01.000+00:00",
                                1000L,
                                "")),
                        1,
                        0,
                        1));
    }

    private MockHttpServletResponse call(String path, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(path).with(who)).andReturn().getResponse();
    }

    private static RequestPostProcessor viewer() {
        return httpBasic("viewer", "dev");
    }

    private static RequestPostProcessor oidc(String subject) {
        return oidcLogin().idToken(token -> token.subject(subject));
    }

    private JsonNode ok(String path) throws Exception {
        MockHttpServletResponse res = call(path, viewer());
        assertThat(res.getStatus()).as(path).isEqualTo(200);
        return mapper.readTree(res.getContentAsString());
    }

    private void verifyGathers(String definitionId, int times) {
        verify(flowable, times(times))
                .queryHistoricProcessInstances(
                        any(), any(), argThat(q -> definitionId.equals(q.processDefinitionId())));
    }

    // ---- RBAC and read scope ------------------------------------------------------------------

    @Test
    void aSignedInUserWithNoRoleIsRefusedWithTheNoEngineRoleCode() throws Exception {
        MockHttpServletResponse res = call("/api/definitions/probe-dev/d-norole/heat", oidc(UNMAPPED));

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(mapper.readTree(res.getContentAsString()).path("code").asText())
                .isEqualTo("no-engine-role");
        verifyNoInteractions(flowable);
    }

    @Test
    void aViewerOnAnotherEngineGetsThePerEngineRefusal() throws Exception {
        MockHttpServletResponse res = call("/api/definitions/probe-prod/d-other/heat", oidc(SCOPED));

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).doesNotContain("no-engine-role");
        verifyNoInteractions(flowable);
    }

    @Test
    void aViewerOnThatEngineReadsIt() throws Exception {
        MockHttpServletResponse res = call("/api/definitions/probe-dev/d-own/heat", oidc(SCOPED));

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(res.getContentAsString())
                        .path("definitionId")
                        .asText())
                .isEqualTo("d-own");
    }

    @Test
    void underScopeEnforcementAnOutOfScopeEngineIsRefusedBeforeAnyEngineCall() throws Exception {
        when(gate.enforced()).thenReturn(true);
        when(gate.canRead(any(Authentication.class), anyString())).thenReturn(false);
        when(gate.canRead(any(Authentication.class), eq("probe-dev"))).thenReturn(true);

        MockHttpServletResponse refused = call("/api/definitions/probe-prod/d-scope/heat", viewer());

        assertThat(refused.getStatus()).isEqualTo(403);
        verify(flowable, never()).getProcessDefinition(any(), any(), anyString());
        assertThat(call("/api/definitions/probe-dev/d-scope/heat", viewer()).getStatus())
                .isEqualTo(200);
    }

    // ---- the response, the cache and the clamp -----------------------------------------------

    @Test
    void theSecondReadIsServedFromTheCacheWithTheSameAsOf() throws Exception {
        JsonNode first = ok("/api/definitions/probe-dev/d-cache/heat");
        JsonNode second = ok("/api/definitions/probe-dev/d-cache/heat");

        assertThat(first.path("cached").asBoolean()).isFalse();
        assertThat(second.path("cached").asBoolean()).isTrue();
        assertThat(second.path("asOf").asText()).isEqualTo(first.path("asOf").asText());
        assertThat(first.path("finishedTotal").asLong()).isEqualTo(7);
        assertThat(first.path("sampledInstances").asInt()).isEqualTo(1);
        assertThat(first.path("requestedN").asInt()).isEqualTo(200);
        assertThat(first.path("refreshThrottledUntil").isNull()
                        || first.path("refreshThrottledUntil").isMissingNode())
                .isTrue();
        verifyGathers("d-cache", 1);
    }

    @Test
    void theCacheIsKeyedBySampleSize() throws Exception {
        ok("/api/definitions/probe-dev/d-keyed/heat?n=50");
        JsonNode other = ok("/api/definitions/probe-dev/d-keyed/heat?n=60");

        assertThat(other.path("cached").asBoolean()).isFalse();
        verifyGathers("d-keyed", 2);
    }

    @Test
    void aRequestAboveTheClampIsClampedNotRefused() throws Exception {
        assertThat(ok("/api/definitions/probe-dev/d-clamp/heat?n=1001")
                        .path("requestedN")
                        .asInt())
                .isEqualTo(1000);
    }

    // ---- refresh throttle -----------------------------------------------------------------------

    @Test
    void oneRefreshPerMinutePerKeyThenTheCachedValueWithThrottledUntil() throws Exception {
        ok("/api/definitions/probe-dev/d-throttle/heat");
        Instant before = Instant.now();
        JsonNode refreshed = ok("/api/definitions/probe-dev/d-throttle/heat?refresh=true");
        JsonNode throttled = ok("/api/definitions/probe-dev/d-throttle/heat?refresh=true");

        assertThat(refreshed.path("cached").asBoolean()).isFalse();
        assertThat(throttled.path("cached").asBoolean()).isTrue();
        assertThat(throttled.path("asOf").asText())
                .isEqualTo(refreshed.path("asOf").asText());
        Instant until = Instant.parse(throttled.path("refreshThrottledUntil").asText());
        assertThat(until).isBetween(before.plusSeconds(59), Instant.now().plusSeconds(60));
        verifyGathers("d-throttle", 2);
    }

    @Test
    void theThrottleIsPerKey() throws Exception {
        ok("/api/definitions/probe-dev/d-key-a/heat?refresh=true");
        JsonNode other = ok("/api/definitions/probe-dev/d-key-b/heat?refresh=true");

        assertThat(other.path("refreshThrottledUntil").isNull()
                        || other.path("refreshThrottledUntil").isMissingNode())
                .isTrue();
        verifyGathers("d-key-b", 1);
    }

    @Test
    void aFailedGatherIsNotCached() throws Exception {
        when(flowable.queryHistoricProcessInstances(
                        any(), any(), argThat(q -> "d-fail".equals(q.processDefinitionId()))))
                .thenThrow(new IllegalStateException("engine down"))
                .thenReturn(samplePage);

        assertThat(call("/api/definitions/probe-dev/d-fail/heat", viewer()).getStatus())
                .isEqualTo(503);
        JsonNode retried = ok("/api/definitions/probe-dev/d-fail/heat");

        assertThat(retried.path("cached").asBoolean()).isFalse();
        verifyGathers("d-fail", 2);
    }

    // ---- single-flight ---------------------------------------------------------------------------

    @Test
    void twoConcurrentCallersShareOneGather() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(flowable.queryHistoricProcessInstances(
                        any(), any(), argThat(q -> "d-flight".equals(q.processDefinitionId()))))
                .thenAnswer(inv -> {
                    entered.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return samplePage;
                });
        AtomicReference<DefinitionHeat> first = new AtomicReference<>();
        AtomicReference<DefinitionHeat> second = new AtomicReference<>();

        Thread a = Thread.ofPlatform().start(() -> first.set(service.heat("probe-dev", "d-flight", null, false)));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Thread b = Thread.ofPlatform().start(() -> second.set(service.heat("probe-dev", "d-flight", null, false)));
        // b is parked: either joined to a's gather (single-flight) or inside a second gather (the bug)
        await().atMost(Duration.ofSeconds(10))
                .until(() -> b.getState() == Thread.State.WAITING || b.getState() == Thread.State.TIMED_WAITING);
        release.countDown();
        a.join(10_000);
        b.join(10_000);

        verifyGathers("d-flight", 1);
        assertThat(first.get()).isNotNull();
        assertThat(second.get()).isNotNull();
        assertThat(second.get().asOf()).isEqualTo(first.get().asOf());
        assertThat(List.of(first.get().cached(), second.get().cached())).containsExactlyInAnyOrder(false, true);
    }
}
