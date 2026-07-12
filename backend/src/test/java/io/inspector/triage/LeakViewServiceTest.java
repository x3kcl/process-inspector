package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.client.FlowablePage;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.LeakViewsResponse;
import io.inspector.dto.LeakViewsResponse.LeakDefinitionCount;
import io.inspector.support.TestEngines;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Rung 2 (unit-test-patterns): the leak-view fan-out over a mocked engine client — the
 * count-only contract, the R-SEM-05 startedBefore-honest SUSPENDED window, per-definition
 * cross-engine merge, and the lower-bound honesty envelope. Real wire shapes are a rung-4
 * (dockerized) concern; these pin the query shape and the merge/honesty semantics.
 */
class LeakViewServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-11T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String A30 = NOW.minus(Duration.ofDays(30)).toString();
    private static final String A90 = NOW.minus(Duration.ofDays(90)).toString();
    private static final String S7 = NOW.minus(Duration.ofDays(7)).toString();

    /** Only these keys may ever appear on a runtime count body — no fabricated suspension-time field. */
    private static final java.util.Set<String> ALLOWED_BODY_KEYS =
            java.util.Set.of("processDefinitionKey", "suspended", "startedBefore", "size", "tenantId");

    private static FlowablePage count(long total) {
        return new FlowablePage(List.of(Map.of()), total, 0, 1);
    }

    private static FlowablePage defs(int total, String... keys) {
        List<Map<String, Object>> rows = java.util.Arrays.stream(keys)
                .<Map<String, Object>>map(k -> Map.of("key", k))
                .toList();
        return new FlowablePage(rows, total, 0, 500);
    }

    private LeakViewService service(ProcessApiClient client, EngineConfig... engines) {
        return new LeakViewService(() -> List.of(engines), client, CLOCK, Duration.ofSeconds(20));
    }

    @Test
    void aggregate_mergesPerDefinition_fromCountOnlyQueries() {
        ProcessApiClient client = mock(ProcessApiClient.class, RETURNS_DEEP_STUBS);
        EngineConfig e1 = TestEngines.engine("e1", "http://e1");
        EngineConfig e2 = TestEngines.engine("e2", "http://e2");
        when(client.listLatestProcessDefinitions(argThat(e -> e != null && e.id().equals("e1")), any(), anyInt()))
                .thenReturn(defs(2, "vacationRequest", "loanApproval"));
        when(client.listLatestProcessDefinitions(argThat(e -> e != null && e.id().equals("e2")), any(), anyInt()))
                .thenReturn(defs(1, "vacationRequest"));
        when(client.queryRuntimeProcessInstances(any(), any(), any())).thenAnswer(inv -> {
            EngineConfig engine = inv.getArgument(0);
            Map<String, Object> body = inv.getArgument(2);
            assertThat(body).containsEntry("size", 1); // count-only, NEVER rows (iron rule)
            assertThat(ALLOWED_BODY_KEYS).containsAll(body.keySet());
            return count(totalFor(engine.id(), body));
        });

        LeakViewsResponse response = service(client, e1, e2).aggregate();

        assertThat(response.windows().activeOver30d()).isEqualTo(A30);
        assertThat(response.windows().activeOver90d()).isEqualTo(A90);
        assertThat(response.windows().suspendedStartedOver7d()).isEqualTo(S7);
        assertThat(response.lowerBound()).isFalse();
        assertThat(response.unavailableEngines()).isEmpty();

        // "vacationRequest: 212 > 30d" — merged 200 (e1) + 12 (e2); sorted leakiest-first.
        assertThat(response.definitions())
                .extracting(LeakDefinitionCount::definitionKey)
                .containsExactly("vacationRequest", "loanApproval");
        LeakDefinitionCount vr = response.definitions().get(0);
        assertThat(vr.activeOver30d()).isEqualTo(212);
        assertThat(vr.activeOver90d()).isEqualTo(40);
        assertThat(vr.suspendedStartedOver7d()).isEqualTo(3);
        LeakDefinitionCount loan = response.definitions().get(1);
        assertThat(loan.activeOver30d()).isEqualTo(12);
        assertThat(loan.suspendedStartedOver7d()).isZero();
    }

    @Test
    void suspendedWindow_isSuspendedTrueAtStartedBefore_neverSuspensionTime() {
        ProcessApiClient client = mock(ProcessApiClient.class, RETURNS_DEEP_STUBS);
        EngineConfig e1 = TestEngines.engine("e1", "http://e1");
        when(client.listLatestProcessDefinitions(any(), any(), anyInt())).thenReturn(defs(1, "vacationRequest"));
        when(client.queryRuntimeProcessInstances(any(), any(), any())).thenAnswer(inv -> {
            EngineConfig engine = inv.getArgument(0);
            return count(totalFor(engine.id(), inv.getArgument(2)));
        });

        service(client, e1).aggregate();

        ArgumentCaptor<Map<String, Object>> bodies = ArgumentCaptor.forClass(Map.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).queryRuntimeProcessInstances(any(), any(), bodies.capture());
        // The one SUSPENDED leg is suspended=true AND uses startedBefore=now−7d — R-SEM-05:
        // age is measured off startTime, the ONLY time the REST API can evaluate.
        List<Map<String, Object>> suspendedLegs = bodies.getAllValues().stream()
                .filter(b -> Boolean.TRUE.equals(b.get("suspended")))
                .toList();
        assertThat(suspendedLegs).isNotEmpty();
        assertThat(suspendedLegs).allSatisfy(b -> {
            assertThat(b.get("startedBefore")).isEqualTo(S7);
            assertThat(ALLOWED_BODY_KEYS).containsAll(b.keySet());
        });
    }

    @Test
    void unreachableEngine_namesIt_andFloorsToLowerBound() {
        ProcessApiClient client = mock(ProcessApiClient.class, RETURNS_DEEP_STUBS);
        EngineConfig e1 = TestEngines.engine("e1", "http://e1");
        EngineConfig e2 = TestEngines.engine("e2", "http://e2");
        when(client.listLatestProcessDefinitions(argThat(e -> e != null && e.id().equals("e1")), any(), anyInt()))
                .thenReturn(defs(1, "vacationRequest"));
        when(client.queryRuntimeProcessInstances(any(), any(), any())).thenAnswer(inv -> {
            EngineConfig engine = inv.getArgument(0);
            if (engine.id().equals("e2")) {
                throw new IllegalStateException("engine e2 is down");
            }
            return count(totalFor(engine.id(), inv.getArgument(2)));
        });

        LeakViewsResponse response = service(client, e1, e2).aggregate();

        assertThat(response.lowerBound()).isTrue();
        assertThat(response.unavailableEngines()).containsExactly("e2");
        // e1 still contributes its honest counts.
        assertThat(response.definitions())
                .extracting(LeakDefinitionCount::definitionKey)
                .contains("vacationRequest");
    }

    @Test
    void truncatedDefinitionList_floorsToLowerBound() {
        ProcessApiClient client = mock(ProcessApiClient.class, RETURNS_DEEP_STUBS);
        EngineConfig e1 = TestEngines.engine("e1", "http://e1");
        // total (9) exceeds returned rows (1) — the key set is a lower bound.
        when(client.listLatestProcessDefinitions(any(), any(), anyInt())).thenReturn(defs(9, "vacationRequest"));
        when(client.queryRuntimeProcessInstances(any(), any(), any())).thenAnswer(inv -> {
            EngineConfig engine = inv.getArgument(0);
            return count(totalFor(engine.id(), inv.getArgument(2)));
        });

        assertThat(service(client, e1).aggregate().lowerBound()).isTrue();
    }

    @Test
    void noLeaks_returnsEmpty_withoutEnumeratingDefinitions() {
        ProcessApiClient client = mock(ProcessApiClient.class, RETURNS_DEEP_STUBS);
        EngineConfig e1 = TestEngines.engine("e1", "http://e1");
        // Every whole-engine pre-check total is zero — no per-definition scan should fire.
        when(client.queryRuntimeProcessInstances(any(), any(), any())).thenReturn(count(0));

        LeakViewsResponse response = service(client, e1).aggregate();

        assertThat(response.definitions()).isEmpty();
        assertThat(response.lowerBound()).isFalse();
        verify(client, never()).listLatestProcessDefinitions(any(), any(), anyInt());
    }

    /** Deterministic fixture: (engine, key, suspended, startedBefore) → count total. */
    private static long totalFor(String engineId, Map<String, Object> body) {
        String key = (String) body.get("processDefinitionKey");
        boolean suspended = Boolean.TRUE.equals(body.get("suspended"));
        String startedBefore = (String) body.get("startedBefore");
        if (key == null) {
            return 5; // whole-engine pre-check: this engine leaks, enumerate its definitions.
        }
        if (engineId.equals("e1") && key.equals("vacationRequest")) {
            if (!suspended && startedBefore.equals(A30)) return 200;
            if (!suspended && startedBefore.equals(A90)) return 40;
            if (suspended && startedBefore.equals(S7)) return 3;
        } else if (engineId.equals("e1") && key.equals("loanApproval")) {
            if (!suspended && startedBefore.equals(A30)) return 12;
        } else if (engineId.equals("e2") && key.equals("vacationRequest")) {
            if (!suspended && startedBefore.equals(A30)) return 12;
        }
        return 0;
    }
}
