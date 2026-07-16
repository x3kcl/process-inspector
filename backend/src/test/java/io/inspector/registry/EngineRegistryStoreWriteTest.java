package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditService;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.RegistryProperties;
import io.inspector.config.RegistryProperties.Source;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.mapping.MappingSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1: the S4 registry write service — the door-AND-service RBAC re-check, SSRF-validate-at-write,
 * the lifecycle state-machine guards, and fail-closed audit ordering — with mocked repo/audit and a
 * REAL {@link RegistryUrlValidator} (literal IPs, no DNS). The end-to-end HTTP + door gate is
 * {@link io.inspector.api.AdminEnginesApiSpringTest}; the real-DB flow is a rung-4 IT.
 */
class EngineRegistryStoreWriteTest {

    private EngineRegistryRepository repo;
    private AuditService audit;
    private ApplicationEventPublisher events;
    private RbacAuthorizer rbac;
    private Authentication admin;
    private RegistryWriteProposalRepository proposals;
    private MappingSource mappingSource;
    private EngineRegistryStore store;

    @BeforeEach
    void setUp() {
        repo = mock(EngineRegistryRepository.class);
        audit = mock(AuditService.class);
        events = mock(ApplicationEventPublisher.class);
        rbac = mock(RbacAuthorizer.class);
        admin = mock(Authentication.class);
        proposals = mock(RegistryWriteProposalRepository.class);
        mappingSource = mock(MappingSource.class);
        when(admin.getName()).thenReturn("reg-admin");
        when(rbac.canAdministerRegistry(admin)).thenReturn(true);
        when(rbac.oidcGroups(admin)).thenReturn(List.of());
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(AuditEntry.class));

        // Egress allows a public /24 (validated by literal IP, no DNS) + loopback for dev.
        RegistryProperties props =
                new RegistryProperties(Source.DB, List.of("93.184.216.0/24", "127.0.0.0/8", "localhost"), Set.of());
        RegistryPinRegistry pinRegistry = new RegistryPinRegistry(new RegistryUrlValidator(), props);
        store = new EngineRegistryStore(
                repo,
                audit,
                events,
                props,
                rbac,
                Clock.fixed(Instant.parse("2026-07-09T12:00:00Z"), ZoneOffset.UTC),
                proposals,
                mappingSource,
                pinRegistry);
    }

    private static RegistryWrite write(String id, String baseUrl, EngineEnvironment env) {
        return new RegistryWrite(
                id, "Name", baseUrl, env, null, null, null, "none", null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /* ---------- RBAC service re-check (door-AND-service) ---------- */

    @Test
    void a_non_registry_admin_is_refused_in_the_service_even_past_the_door() {
        when(rbac.canAdministerRegistry(admin)).thenReturn(false);

        assertThatThrownBy(() -> store.add(
                        write("e1", "https://93.184.216.34/service", EngineEnvironment.TEST),
                        admin,
                        "adding a new engine"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REGISTRY_ADMIN");
        verify(repo, never()).save(any());
    }

    @Test
    void editBaseUrl_re_checks_the_fleet_grant_in_the_service_and_never_reads_the_row() {
        // S6: the S3 reload seam used to skip requireRegistryAdmin. A non-admin must be refused
        // BEFORE the row is even loaded — no lookup, no audit, no write, no reload event.
        when(rbac.canAdministerRegistry(admin)).thenReturn(false);

        assertThatThrownBy(
                        () -> store.editBaseUrl("engine-a", "https://93.184.216.34/service-EDITED", admin, "seam edit"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REGISTRY_ADMIN");
        verify(repo, never()).findByIdAndRemovedAtIsNull(any());
        verify(audit, never()).beginPending(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    /* ---------- SSRF validate-at-write ---------- */

    @Test
    void add_rejects_an_ssrf_base_url_before_any_audit_or_write() {
        when(repo.existsById("e1")).thenReturn(false);

        // The metadata IP is rejected (here by the egress allowlist rail — it is not allowlisted;
        // the encoding-decode-to-denylist path is exhaustively proven by S1's hostile corpus). The
        // load-bearing property: NOTHING was audited or persisted — validation is before the write.
        assertThatThrownBy(() -> store.add(
                        write("e1", "http://169.254.169.254/", EngineEnvironment.DEV), admin, "adding a new engine"))
                .isInstanceOf(ResponseStatusException.class);
        verify(audit, never()).beginPending(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void add_accepts_a_public_allowlisted_url_and_is_born_draft_read_only() {
        when(repo.existsById("orders")).thenReturn(false);

        EngineRegistryRow row = store.add(
                write("orders", "https://93.184.216.34/flowable-rest/service", EngineEnvironment.TEST),
                admin,
                "onboarding orders");

        assertThat(row.getLifecycle()).isEqualTo("draft");
        assertThat(row.getMode()).isEqualTo("read-only"); // trust earned by a probe, not asserted
        assertThat(row.getSource()).isEqualTo("ui");
        verify(repo).save(row);
        verify(events).publishEvent(new RegistryChangedEvent("orders"));
    }

    /* ---------- id + duplicate ---------- */

    @Test
    void add_rejects_a_bad_slug_and_a_duplicate_id() {
        assertThatThrownBy(() -> store.add(
                        write("Bad Id!", "https://93.184.216.34/s", EngineEnvironment.TEST), admin, "bad slug attempt"))
                .isInstanceOf(ResponseStatusException.class);

        when(repo.existsById("dup")).thenReturn(true);
        assertThatThrownBy(() -> store.add(
                        write("dup", "https://93.184.216.34/s", EngineEnvironment.TEST), admin, "duplicate attempt"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already registered");
    }

    /* ---------- lifecycle state-machine guards ---------- */

    private EngineRegistryRow rowWithLifecycle(String id, String lifecycle) {
        EngineRegistryRow row = new EngineRegistryRow();
        row.setId(id);
        row.setLifecycle(lifecycle);
        row.setMode("read-only");
        return row;
    }

    @Test
    void enable_requires_a_probed_or_disabled_engine() {
        when(repo.findByIdAndRemovedAtIsNull("e")).thenReturn(Optional.of(rowWithLifecycle("e", "draft")));
        assertThatThrownBy(() -> store.enable("e", false, admin, "enabling a raw draft"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("probed or disabled");
    }

    @Test
    void disable_requires_an_active_engine_and_remove_requires_a_disabled_one() {
        when(repo.findByIdAndRemovedAtIsNull("e")).thenReturn(Optional.of(rowWithLifecycle("e", "draft")));
        assertThatThrownBy(() -> store.disable("e", admin, "disabling a non-active"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.remove("e", admin, "removing a non-disabled"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disable the engine before removing");
    }

    @Test
    void purge_requires_a_tombstoned_engine() {
        EngineRegistryRow live = rowWithLifecycle("e", "disabled"); // removedAt null → not tombstoned
        when(repo.findById("e")).thenReturn(Optional.of(live));
        assertThatThrownBy(() -> store.purge("e", admin, "purging a live engine"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("removed (tombstoned)");
    }

    @Test
    void enable_read_write_on_a_probed_non_prod_engine_transitions_active_and_reloads() {
        // No environment set (null != "prod") ⇒ single-actor, matching the existing typed-token scope.
        when(repo.findByIdAndRemovedAtIsNull("e")).thenReturn(Optional.of(rowWithLifecycle("e", "probed")));

        EngineRegistryStore.Outcome outcome = store.enable("e", true, admin, "enabling read-write after probe");

        assertThat(outcome.status()).isEqualTo("applied");
        assertThat(outcome.row().getLifecycle()).isEqualTo("active");
        assertThat(outcome.row().getMode()).isEqualTo("read-write");
        verify(events).publishEvent(new RegistryChangedEvent("e"));
    }

    /* ---------- probe result recording (issue #223) ---------- */

    @Test
    void record_probe_persists_the_real_detail_to_the_audit_snippet_not_a_generic_literal() {
        when(repo.findByIdAndRemovedAtIsNull("e")).thenReturn(Optional.of(rowWithLifecycle("e", "draft")));
        AuditEntry entry = mock(AuditEntry.class);
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entry);

        store.recordProbe("e", false, admin, "java.net.ConnectException: Connection refused");

        verify(audit)
                .close(
                        eq(entry),
                        eq(io.inspector.audit.AuditOutcome.failed),
                        eq((Integer) null),
                        eq("java.net.ConnectException: Connection refused"),
                        eq(true));
    }

    @Test
    void record_probe_falls_back_to_a_generic_snippet_when_detail_is_blank() {
        when(repo.findByIdAndRemovedAtIsNull("e")).thenReturn(Optional.of(rowWithLifecycle("e", "draft")));
        AuditEntry entry = mock(AuditEntry.class);
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entry);

        store.recordProbe("e", true, admin, null);

        verify(audit)
                .close(eq(entry), eq(io.inspector.audit.AuditOutcome.ok), eq((Integer) null), eq("probed"), eq(true));
    }

    /* ---------- four-eyes (R-SAFE-08, #91) ---------- */

    private EngineRegistryRow rowWithLifecycleAndEnv(String id, String lifecycle, String environment) {
        EngineRegistryRow row = rowWithLifecycle(id, lifecycle);
        row.setEnvironment(environment);
        return row;
    }

    @Test
    void enable_read_write_on_a_prod_engine_is_proposed_not_applied() {
        when(repo.findByIdAndRemovedAtIsNull("e"))
                .thenReturn(Optional.of(rowWithLifecycleAndEnv("e", "probed", "prod")));
        when(mappingSource.allFleetGrants())
                .thenReturn(List.of(new MappingSource.FleetGrantRow(
                        "registry-admins", io.inspector.security.mapping.FleetGrant.REGISTRY_ADMIN, "ui")));
        when(proposals.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EngineRegistryStore.Outcome outcome = store.enable("e", true, admin, "prod read-write flip");

        assertThat(outcome.status()).isEqualTo("proposed");
        assertThat(outcome.row()).isNull();
        assertThat(outcome.eligibleApproverGroups()).containsExactly("registry-admins");
        verify(repo, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void remove_and_purge_always_propose_regardless_of_environment() {
        when(repo.findByIdAndRemovedAtIsNull("e")).thenReturn(Optional.of(rowWithLifecycle("e", "disabled")));
        EngineRegistryRow tombstoned = rowWithLifecycle("p", "disabled");
        tombstoned.setRemovedAt(Instant.parse("2026-07-01T00:00:00Z"));
        when(repo.findById("p")).thenReturn(Optional.of(tombstoned));
        when(mappingSource.allFleetGrants())
                .thenReturn(List.of(new MappingSource.FleetGrantRow(
                        "registry-admins", io.inspector.security.mapping.FleetGrant.REGISTRY_ADMIN, "ui")));
        when(proposals.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EngineRegistryStore.Outcome removeOutcome = store.remove("e", admin, "removing a disabled engine");
        assertThat(removeOutcome.status()).isEqualTo("proposed");

        EngineRegistryStore.Outcome purgeOutcome = store.purge("p", admin, "purging a tombstoned engine");
        assertThat(purgeOutcome.status()).isEqualTo("proposed");

        verify(repo, never()).save(any());
        verify(repo, never()).delete(any());
    }

    @Test
    void propose_with_no_independent_approver_is_refused() {
        when(repo.findByIdAndRemovedAtIsNull("e")).thenReturn(Optional.of(rowWithLifecycle("e", "disabled")));
        // The only REGISTRY_ADMIN group IS the proposer's own — no eligible independent approver.
        when(rbac.oidcGroups(admin)).thenReturn(List.of("registry-admins"));
        when(mappingSource.allFleetGrants())
                .thenReturn(List.of(new MappingSource.FleetGrantRow(
                        "registry-admins", io.inspector.security.mapping.FleetGrant.REGISTRY_ADMIN, "ui")));

        assertThatThrownBy(() -> store.remove("e", admin, "removing a disabled engine"))
                .isInstanceOf(EngineRegistryStore.NoEligibleApproverException.class)
                .hasMessageContaining("No eligible independent REGISTRY_ADMIN approver");
        verify(proposals, never()).save(any());
    }

    @Test
    void approve_refuses_self_approval_and_a_non_independent_approver() {
        RegistryWriteProposal proposal = new RegistryWriteProposal(
                "reg-admin",
                "registry-admins",
                "e",
                RegistryChange.Kind.REMOVE,
                "remove engine 'e'",
                "removing a disabled engine",
                Instant.parse("2026-07-09T12:00:00Z"),
                Instant.parse("2026-07-10T12:00:00Z"));
        when(proposals.findById(1L)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> store.approve(1L, admin))
                .isInstanceOf(EngineRegistryStore.IneligibleApproverException.class)
                .hasMessageContaining("cannot approve their own");

        Authentication sameGroupApprover = mock(Authentication.class);
        when(sameGroupApprover.getName()).thenReturn("another-admin");
        when(rbac.canAdministerRegistry(sameGroupApprover)).thenReturn(true);
        when(rbac.oidcGroups(sameGroupApprover)).thenReturn(List.of("registry-admins"));
        when(mappingSource.allFleetGrants())
                .thenReturn(List.of(new MappingSource.FleetGrantRow(
                        "registry-admins", io.inspector.security.mapping.FleetGrant.REGISTRY_ADMIN, "ui")));

        assertThatThrownBy(() -> store.approve(1L, sameGroupApprover))
                .isInstanceOf(EngineRegistryStore.IneligibleApproverException.class)
                .hasMessageContaining("not independent");
    }

    @Test
    void approve_by_an_independent_admin_applies_the_change() {
        RegistryWriteProposal proposal = new RegistryWriteProposal(
                "reg-admin",
                "registry-admins",
                "e",
                RegistryChange.Kind.REMOVE,
                "remove engine 'e'",
                "removing a disabled engine",
                Instant.parse("2026-07-09T12:00:00Z"),
                Instant.parse("2026-07-10T12:00:00Z"));
        when(proposals.findById(1L)).thenReturn(Optional.of(proposal));
        when(repo.findById("e")).thenReturn(Optional.of(rowWithLifecycle("e", "disabled")));

        Authentication approver = mock(Authentication.class);
        when(approver.getName()).thenReturn("registry-admin-2");
        when(rbac.canAdministerRegistry(approver)).thenReturn(true);
        when(rbac.oidcGroups(approver)).thenReturn(List.of("other-admins"));
        when(mappingSource.allFleetGrants())
                .thenReturn(List.of(
                        new MappingSource.FleetGrantRow(
                                "registry-admins", io.inspector.security.mapping.FleetGrant.REGISTRY_ADMIN, "ui"),
                        new MappingSource.FleetGrantRow(
                                "other-admins", io.inspector.security.mapping.FleetGrant.REGISTRY_ADMIN, "ui")));

        EngineRegistryStore.Outcome outcome = store.approve(1L, approver);

        assertThat(outcome.status()).isEqualTo("applied");
        assertThat(proposal.getStatus()).isEqualTo(RegistryWriteProposal.Status.APPROVED);
        assertThat(proposal.getApprover()).isEqualTo("registry-admin-2");
        verify(events).publishEvent(new RegistryChangedEvent("e"));
    }

    @Test
    void approve_refuses_an_expired_proposal() {
        RegistryWriteProposal proposal = new RegistryWriteProposal(
                "reg-admin",
                "registry-admins",
                "e",
                RegistryChange.Kind.REMOVE,
                "remove engine 'e'",
                "removing a disabled engine",
                Instant.parse("2026-07-01T12:00:00Z"),
                Instant.parse("2026-07-02T12:00:00Z")); // expired relative to the fixed clock (2026-07-09)
        when(proposals.findById(1L)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> store.approve(1L, admin)).isInstanceOf(IllegalStateException.class);
        assertThat(proposal.getStatus()).isEqualTo(RegistryWriteProposal.Status.EXPIRED);
    }
}
