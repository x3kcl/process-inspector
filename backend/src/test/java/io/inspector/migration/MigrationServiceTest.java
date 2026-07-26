package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.action.ActionResult;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.TicketPolicy;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.audit.ProtectionGuard;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.AuditProperties;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.OidcProperties;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.reauth.DangerousActionReauthGate;
import io.inspector.security.reauth.ReauthRequiredException;
import io.inspector.support.TestEngines;
import io.inspector.surgery.BpmnStructureService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * Rung 1 for the migration reauth gate (issue #295, IDP-SECURITY.md §5, R-SAFE-07): migrate is a
 * tier-3 action (SPEC §5) but is NOT an {@code ActionVerb} (MIGRATE has no place in that enum),
 * so {@code CorrectiveActionService}'s {@code verb.tier() >= 3} branch can never cover it — this
 * class proves the dedicated {@code reauth.enforce(auth)} call in {@code MigrationService.execute()}
 * closes that gap, mirroring {@code CorrectiveActionServiceTest}'s tier-3 terminate reauth tests.
 * Engine wire semantics (the migrate call actually landing) are rung 4 — {@code MigrationIT}; here
 * {@code client} is our seam, mocked for guard ordering only.
 */
class MigrationServiceTest {

    private static final String DEV = "dev-engine";
    private static final String FROM_DEF_ID = "demoMigration:1:def-1";
    private static final String TO_DEF_ID = "demoMigration:2:def-2";

    /** Fixed clock anchor for the OIDC freshness cases; the 15-min default window is applied off it. */
    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");

    private final ProcessApiClient client = mock(ProcessApiClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private final ProtectedDefinitionRepository protectedDefinitions = mock(ProtectedDefinitionRepository.class);
    private final Authentication admin = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");

    private MigrationService service;
    private AuditEntry pendingEntry;

    @BeforeEach
    void setUp() throws IOException {
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(TestEngines.engine(DEV, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));
        registry.updateHealth(DEV, probed("6.8.0"));

        service = new MigrationService(
                registry,
                client,
                new BpmnStructureService(client),
                rbac,
                audit,
                new ProtectionGuard(protectedInstances, protectedDefinitions, rbac),
                new TicketPolicy(new AuditProperties(null, null, null)),
                new DangerousActionReauthGate(new OidcProperties(null, false, null), Clock.fixed(NOW, ZoneOffset.UTC)));

        when(rbac.hasRoleOn(any(), any(), any())).thenReturn(true);
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());
        when(protectedDefinitions.findById(any())).thenReturn(Optional.empty());

        pendingEntry = new AuditEntry(
                UUID.nameUUIDFromBytes("audit".getBytes(StandardCharsets.UTF_8)),
                "corr-1",
                "admin",
                Instant.parse("2026-07-20T12:00:00Z"),
                DEV,
                null,
                "pi-1",
                MigrationService.MIGRATE_ACTION,
                null,
                null,
                null,
                false);
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pendingEntry);

        // -- the server-fresh reads plan() performs, shared by preview() and execute() --
        Map<String, Object> running = new HashMap<>();
        running.put("id", "pi-1");
        running.put("processDefinitionId", FROM_DEF_ID);
        running.put("suspended", false);
        running.put("businessKey", "ORD-77");
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(running);

        // Default target resolution: no toDefinitionId/toVersion in the request → latest deployed.
        Map<String, Object> targetDef = new HashMap<>();
        targetDef.put("id", TO_DEF_ID);
        targetDef.put("key", "demoMigration");
        targetDef.put("version", 2);
        when(client.latestProcessDefinitionByKey(any(), eq(CallPriority.INTERACTIVE), eq("demoMigration")))
                .thenReturn(new FlowablePage(List.of(targetDef), 1, 0, 1));

        // Same fixture BPMN for "from" and "to" — the diff outcome (auto-mapped vs. flagged) is
        // irrelevant to the reauth-ordering tests below; both must simply parse.
        when(client.getProcessDefinitionModel(any(), eq(CallPriority.INTERACTIVE), eq(FROM_DEF_ID)))
                .thenReturn(modelJson());
        when(client.processDefinitionResourceData(any(), eq(CallPriority.INTERACTIVE), eq(FROM_DEF_ID)))
                .thenReturn(xml());
        when(client.getProcessDefinitionModel(any(), eq(CallPriority.INTERACTIVE), eq(TO_DEF_ID)))
                .thenReturn(modelJson());
        when(client.processDefinitionResourceData(any(), eq(CallPriority.INTERACTIVE), eq(TO_DEF_ID)))
                .thenReturn(xml());

        when(client.listExecutions(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), anyInt()))
                .thenReturn(new FlowablePage(List.of(Map.of("id", "pi-1", "activityId", "stepOne")), 1, 0, 100));
    }

    private static EngineHealth probed(String version) {
        return new EngineHealth(
                true, version, null, 0, EngineCapabilities.fromVersion(version, true), null, null, null);
    }

    private static Map<String, Object> modelJson() throws IOException {
        try (InputStream in = MigrationServiceTest.class.getResourceAsStream("/surgery/demo-flow-surgery-model.json")) {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(in, Map.class);
        }
    }

    private static String xml() throws IOException {
        try (InputStream in = MigrationServiceTest.class.getResourceAsStream("/surgery/demo-flow-surgery.bpmn20.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MigrationRequest requestWithoutCas() {
        return new MigrationRequest(null, null, null, "operator requested migration", null, "ORD-77", null, null);
    }

    /** An OIDC session whose auth_time is {@code age} old (null age = the IdP asserted no auth_time). */
    private static Authentication oidcSession(Duration age, String... roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "u-1");
        if (age != null) {
            claims.put("auth_time", NOW.minus(age).getEpochSecond());
        }
        OidcIdToken idToken =
                new OidcIdToken("id-tok", NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), claims);
        Collection<GrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .toList();
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "oidc");
    }

    /* ---------------- dangerous-set re-auth (R-SAFE-07, IDP-SECURITY.md §5, issue #295) ---------------- */

    @Test
    void staleOidcSessionDemandsReauthBeforeTheCasCheckAndBeforeAudit() {
        // 20 min > the 15-min window → the freshness challenge fires FIRST: not preview-required
        // (the CAS check is downstream of reauth), and no MUTATING engine call is dispatched —
        // plan()'s shared server-fresh reads (restate/target/structure) already ran, same as they
        // do for preview(), but migrateInstance() is never reached.
        Authentication stale = oidcSession(Duration.ofMinutes(20), "ROLE_ADMIN");

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", requestWithoutCas(), stale))
                .isInstanceOf(ReauthRequiredException.class)
                .satisfies(e -> assertThat(((ReauthRequiredException) e).freshnessWindowSeconds())
                        .isEqualTo(900));
        verify(client, never()).migrateInstance(any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void freshOidcSessionSkipsReauthAndReachesTheCasCheck() {
        // 5 min < window → fresh: reauth passes and control reaches assertNotMovedSincePreview,
        // which then refuses because the request carries no expectedFromDefinitionId/digest (the
        // request/no-preview shape) — proving the gate lets a fresh session through to the next rail.
        Authentication fresh = oidcSession(Duration.ofMinutes(5), "ROLE_ADMIN");

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", requestWithoutCas(), fresh))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("preview-required"));
        verify(client, never()).migrateInstance(any(), any(), any(), any());
    }

    @Test
    void anAbsentAuthTimeFailsClosedEvenForAdmin() {
        // No auth_time claim at all (a nonconforming IdP) → SessionFreshness fails closed, same
        // challenge as a stale session (DangerousActionReauthGate's documented floor).
        Authentication noAuthTime = oidcSession(null, "ROLE_ADMIN");

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", requestWithoutCas(), noAuthTime))
                .isInstanceOf(ReauthRequiredException.class);
        verify(client, never()).migrateInstance(any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void devBasicSessionIsExemptFromTheFreshnessCheck() {
        // A dev Basic/form session (never an OAuth2AuthenticationToken) is intrinsically fresh —
        // reaches the same CAS-required refusal as the fresh-OIDC case above, never a 401 challenge.
        assertThatThrownBy(() -> service.execute(DEV, "pi-1", requestWithoutCas(), admin))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("preview-required"));
        verify(client, never()).migrateInstance(any(), any(), any(), any());
    }

    @Test
    void previewIsNeverGatedByReauthEvenOnAStaleOidcSession() {
        // The issue's explicit caveat: preview() is read-only and must NOT be gated (gating it
        // would fight the /api/me hint protocol) — a stale session still gets a preview.
        Authentication stale = oidcSession(Duration.ofMinutes(20), "ROLE_ADMIN");

        MigrationPreview preview = service.preview(DEV, "pi-1", requestWithoutCas(), stale);

        assertThat(preview.fromDefinitionId()).isEqualTo(FROM_DEF_ID);
        verifyNoInteractions(audit);
    }

    /* ---------------- happy path, past the gate: proves the gate does not wedge a valid request ---------------- */

    @Test
    void freshSessionWithAValidCasCarriesThroughToTheEngineCall() {
        Authentication fresh = oidcSession(Duration.ofMinutes(5), "ROLE_ADMIN");
        MigrationPreview preview = service.preview(DEV, "pi-1", requestWithoutCas(), fresh);
        MigrationRequest execRequest = new MigrationRequest(
                null,
                null,
                null,
                "operator requested migration for INC-42",
                null,
                "ORD-77",
                preview.fromDefinitionId(),
                preview.activityStateDigest());

        ActionResult result = service.execute(DEV, "pi-1", execRequest, fresh);

        assertThat(result.outcome()).isEqualTo("ok");
        verify(client).migrateInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), any());
        verify(audit).close(eq(pendingEntry), eq(AuditOutcome.ok), eq(200), any(), eq(true));
    }
}
