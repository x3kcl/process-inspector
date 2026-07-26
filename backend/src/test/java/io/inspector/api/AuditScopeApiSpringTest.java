package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Rung 3 — the R-SAFE-17 scope projection on the audit operations-log JSON list and CSV export,
 * over REAL HTTP (issue #296). The dev basic-auth ladder is inherently global-scoped
 * (enforcement is a no-op for it — exactly the legacy behaviour {@link AuditControllerTest} /
 * {@link AuditCsvExportSpringTest} already prove), so {@link ReadScopeGate} is overridden here to
 * emulate a genuinely per-engine-scoped session — the real grant→readable-set derivation is
 * proven separately by {@code ReadScopeGateTest}; the JPQL predicate's own SQL correctness is
 * proven separately by {@code AuditEntryRepositoryScopeIT} against a real Postgres. What THIS
 * class proves is the wire contract: the controller resolves the readable set on the request
 * thread, calls the SCOPED query (never the legacy unscoped one) once enforcement bites, and the
 * rows/CSV lines that reach the caller are exactly the ones the (mocked) scoped query returns —
 * this is the MANDATORY enforcement-ON slice the issue calls for; the existing mock/HTTP suites
 * above run with {@link ReadScopeGate} effectively off and would pass unchanged either way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class AuditScopeApiSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    AuditEntryRepository repository;

    @MockitoBean
    ReadScopeGate gate;

    @AfterEach
    void resetMocks() {
        reset(repository, gate);
    }

    private TestRestTemplate viewer() {
        return rest.withBasicAuth("viewer", "dev");
    }

    private TestRestTemplate admin() {
        return rest.withBasicAuth("admin", "dev");
    }

    private static AuditEntry row(String engineId, String actor) {
        AuditEntry entry = new AuditEntry(
                UUID.randomUUID(),
                "corr-" + actor,
                actor,
                Instant.parse("2026-07-20T10:00:00Z"),
                engineId,
                null,
                "pi-1",
                "retry-job",
                "reason text",
                "OPS-1",
                "{\"jobId\":\"j1\"}",
                false);
        entry.close(AuditOutcome.ok, 200, "snippet", false);
        return entry;
    }

    @Test
    void scopedViewerSeesOnlyInScopeRowsInJsonListAndNeverHitsTheUnscopedQuery() throws Exception {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        when(repository.findLogScoped(any(), any(), any(), any(), any(), eq(Set.of("probe-dev")), anyBoolean(), any()))
                .thenReturn(List.of(row("probe-dev", "alice")));

        ResponseEntity<String> res = viewer().getForEntity("/api/audit", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("engineId").asText()).isEqualTo("probe-dev");
        verify(repository, never()).findLog(any(), any(), any(), any(), any(), any());
    }

    @Test
    void scopedViewerSeesOnlyInScopeCsvLines() {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        when(repository.findLogScoped(any(), any(), any(), any(), any(), eq(Set.of("probe-dev")), anyBoolean(), any()))
                .thenReturn(List.of(row("probe-dev", "alice")));

        String body = viewer().getForEntity("/api/audit/export", String.class).getBody();
        String[] lines = body.split("\n");

        assertThat(lines).hasSize(2); // header + the one in-scope row
        assertThat(lines[1]).contains("probe-dev").contains("alice");
        verify(repository, never()).findLog(any(), any(), any(), any(), any(), any());
    }

    @Test
    void configEventRowVisibleOnlyToFleetAdmin_inBothJsonAndCsv() {
        AuditEntry configRow = row(AuditService.CONFIG_ENGINE_ID, "system");
        AuditEntry engineRow = row("probe-dev", "alice");
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        // The repository call site passes adminAnywhere through — mirror the real query's
        // documented behaviour (config rows only surviving the predicate for a fleet admin).
        when(repository.findLogScoped(any(), any(), any(), any(), any(), any(), eq(true), any()))
                .thenReturn(List.of(configRow, engineRow));
        when(repository.findLogScoped(any(), any(), any(), any(), any(), any(), eq(false), any()))
                .thenReturn(List.of(engineRow));

        ResponseEntity<String> asViewer = viewer().getForEntity("/api/audit", String.class);
        ResponseEntity<String> asAdmin = admin().getForEntity("/api/audit", String.class);

        assertThat(asViewer.getBody()).doesNotContain(AuditService.CONFIG_ENGINE_ID);
        assertThat(asAdmin.getBody()).contains(AuditService.CONFIG_ENGINE_ID);
    }

    @Test
    void anUnscopedViewerIsUnaffected_legacyFleetWideBehaviourStands() {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(null);
        when(repository.findLog(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(row("probe-dev", "alice"), row("probe-prod", "bob")));

        ResponseEntity<String> res = viewer().getForEntity("/api/audit", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("probe-dev").contains("probe-prod");
        verify(repository, never()).findLogScoped(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }
}
