package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private EngineRegistryStore store;

    @BeforeEach
    void setUp() {
        repo = mock(EngineRegistryRepository.class);
        audit = mock(AuditService.class);
        events = mock(ApplicationEventPublisher.class);
        rbac = mock(RbacAuthorizer.class);
        admin = mock(Authentication.class);
        when(admin.getName()).thenReturn("reg-admin");
        when(rbac.canAdministerRegistry(admin)).thenReturn(true);
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(AuditEntry.class));

        // Egress allows a public /24 (validated by literal IP, no DNS) + loopback for dev.
        RegistryProperties props =
                new RegistryProperties(Source.DB, List.of("93.184.216.0/24", "127.0.0.0/8", "localhost"), Set.of());
        store = new EngineRegistryStore(
                repo,
                audit,
                events,
                new RegistryUrlValidator(),
                props,
                rbac,
                Clock.fixed(Instant.parse("2026-07-09T12:00:00Z"), ZoneOffset.UTC));
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
    void enable_read_write_on_a_probed_engine_transitions_active_and_reloads() {
        when(repo.findByIdAndRemovedAtIsNull("e")).thenReturn(Optional.of(rowWithLifecycle("e", "probed")));

        EngineRegistryRow row = store.enable("e", true, admin, "enabling read-write after probe");

        assertThat(row.getLifecycle()).isEqualTo("active");
        assertThat(row.getMode()).isEqualTo("read-write");
        verify(events).publishEvent(new RegistryChangedEvent("e"));
    }
}
