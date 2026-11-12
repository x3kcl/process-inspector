package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.security.ScopeMappingService;
import io.inspector.security.mapping.AccessMappingAdminService.ApexInvariantException;
import io.inspector.security.mapping.AccessMappingAdminService.CrudDisabledException;
import io.inspector.security.mapping.AccessMappingAdminService.IneligibleApproverException;
import io.inspector.security.mapping.AccessMappingAdminService.Outcome;
import io.inspector.security.mapping.MappingSource.FleetGrantRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 (#464): the governed mapping-CRUD rails of {@link AccessMappingAdminService} — CRUD gate
 * (≥2 apex groups), the ≥2-apex removal invariant, four-eyes routing, approver independence, proposal
 * expiry and integrity, fail-closed audit ordering and the ACCESS_ADMIN alert — over mocked
 * repositories. The DB round-trip itself is {@code AccessMappingAdminDbIT}'s job.
 */
class AccessMappingAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    private final MappingStore store = mock(MappingStore.class);
    private final MappingSource source = mock(MappingSource.class);
    private final DbMappingSource dbSource = mock(DbMappingSource.class);
    private final AccessGrantProposalRepository proposals = mock(AccessGrantProposalRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final SecurityAlertChannel alert = mock(SecurityAlertChannel.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final ScopeMappingService file = mock(ScopeMappingService.class);
    private final GroupScopeGrantRepository ladderRepo = mock(GroupScopeGrantRepository.class);
    // Boot's auto-configured Jackson 2 mapper (what production injects) tolerates unknown properties:
    // GrantChange serializes its isAdd()/isLadder() accessors, which the record cannot read back.
    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private AccessMappingAdminService service(String envApex) {
        return new AccessMappingAdminService(
                store,
                source,
                dbSource,
                proposals,
                audit,
                alert,
                rbac,
                mapper,
                clock,
                file,
                new MappingProperties("db", envApex),
                ladderRepo);
    }

    private void apexGroups(String... groups) {
        when(source.allFleetGrants())
                .thenReturn(Arrays.stream(groups)
                        .map(g -> new FleetGrantRow(g, FleetGrant.ACCESS_ADMIN, "ui"))
                        .toList());
    }

    private Authentication user(String name, String... groups) {
        Authentication auth = new UsernamePasswordAuthenticationToken(name, "x", List.of());
        when(rbac.oidcGroups(auth)).thenReturn(List.of(groups));
        return auth;
    }

    /* ------------------------------ submit ------------------------------ */

    @Test
    void crudIsDisabledWithFewerThanTwoIndependentApexGroups() {
        apexGroups("admins-a");

        assertThatThrownBy(() -> service("admins-a")
                        .submit(GrantChange.ladderRemove("ops", Role.VIEWER, "e1", "t1"), "r", user("alice")))
                .isInstanceOf(CrudDisabledException.class);
        verifyNoInteractions(store, audit);
    }

    static Stream<Arguments> singleActorChanges() {
        return Stream.of(
                // narrowing a ladder grant is always single-actor
                Arguments.of(GrantChange.ladderRemove("ops", Role.ADMIN, "*", "*"), "grant-remove"),
                // a scoped ladder add to a group the editor is NOT in
                Arguments.of(GrantChange.ladderAdd("ops", Role.OPERATOR, "e1", "t1"), "grant-add"),
                // a VIEWER wildcard is not a breadth widening
                Arguments.of(GrantChange.ladderAdd("ops", Role.VIEWER, "*", "*"), "grant-add"));
    }

    @ParameterizedTest
    @MethodSource("singleActorChanges")
    void aNonWideningChangeAppliesAuditedAndRefreshesTheCache(GrantChange change, String action) {
        apexGroups("admins-a", "admins-b");

        Outcome outcome = service("admins-a").submit(change, "ticket-1", user("alice", "admins-a"));

        assertThat(outcome.status()).isEqualTo("applied");
        assertThat(outcome.proposalId()).isNull();
        assertThat(outcome.summary()).isEqualTo(change.summary());
        var order = org.mockito.Mockito.inOrder(audit, store, dbSource);
        order.verify(audit)
                .beginPending(
                        eq("alice"),
                        eq(AuditService.CONFIG_ENGINE_ID),
                        isNull(),
                        eq("ops"),
                        eq(action),
                        eq("ticket-1"),
                        isNull(),
                        anyMap());
        order.verify(store).apply(change);
        order.verify(audit).close(any(), eq(AuditOutcome.ok), isNull(), eq(change.summary()), eq(true));
        order.verify(dbSource).refresh();
        verifyNoInteractions(alert, proposals);
    }

    @Test
    void aFailingStoreWriteClosesTheAuditAsFailedAndNeverRefreshes() {
        apexGroups("admins-a", "admins-b");
        GrantChange change = GrantChange.ladderRemove("ops", Role.VIEWER, "e1", "t1");
        doThrow(new IllegalArgumentException("no such grant")).when(store).apply(change);

        assertThatThrownBy(() -> service("admins-a").submit(change, "r", user("alice")))
                .hasMessageContaining("no such grant");
        verify(audit).close(any(), eq(AuditOutcome.failed), isNull(), eq(change.summary()), eq(false));
        verify(dbSource, never()).refresh();
    }

    static Stream<Arguments> fourEyesChanges() {
        return Stream.of(
                // (a) self-widen: the editor is in the affected group
                Arguments.of(GrantChange.ladderAdd("admins-a", Role.VIEWER, "e1", "t1"), false),
                // (b) breadth: ≥OPERATOR on a wildcard engine or tenant
                Arguments.of(GrantChange.ladderAdd("ops", Role.OPERATOR, "*", "t1"), false),
                Arguments.of(GrantChange.ladderAdd("ops", Role.ADMIN, "e1", "*"), false),
                // (c) any fleet create; ACCESS_ADMIN also fires the detective alert
                Arguments.of(GrantChange.fleetAdd("ops", FleetGrant.REGISTRY_ADMIN), false),
                Arguments.of(GrantChange.fleetAdd("ops", FleetGrant.ACCESS_ADMIN), true));
    }

    @ParameterizedTest
    @MethodSource("fourEyesChanges")
    void aWideningChangeIsParkedAsAProposalForAnIndependentApprover(GrantChange change, boolean alerts) {
        apexGroups("admins-a", "admins-b");
        when(proposals.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Outcome outcome = service("admins-a").submit(change, "needs it", user("alice", "admins-a"));

        assertThat(outcome.status()).isEqualTo("proposed");
        assertThat(outcome.summary()).isEqualTo(change.summary());
        // the affected group can never approve its own widening
        assertThat(outcome.eligibleApproverGroups())
                .doesNotContain(change.group())
                .isNotEmpty();
        var saved = org.mockito.ArgumentCaptor.forClass(AccessGrantProposal.class);
        verify(proposals).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AccessGrantProposal.Status.PENDING);
        assertThat(saved.getValue().getProposer()).isEqualTo("alice");
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        verify(audit).recordConfigEvent(eq("grant-proposal"), eq("alice"), eq(true), anyMap());
        verify(store, never()).apply(any());
        if (alerts) {
            verify(alert).fire(eq("access-admin-grant-proposed"), anyString());
        } else {
            verifyNoInteractions(alert);
        }
    }

    @Test
    void removingAnApexIsRefusedWhenItWouldLeaveFewerThanTwo() {
        apexGroups("admins-a", "admins-b");

        assertThatThrownBy(() -> service("admins-a")
                        .submit(GrantChange.fleetRemove("admins-b", FleetGrant.ACCESS_ADMIN), "r", user("alice")))
                .isInstanceOf(ApexInvariantException.class);
        verifyNoInteractions(proposals, store);
    }

    @Test
    void removingAnApexWithThreeLeftIsAllowedButStillFourEyes() {
        apexGroups("admins-a", "admins-b", "admins-c");
        when(proposals.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Outcome outcome = service("admins-a")
                .submit(GrantChange.fleetRemove("admins-c", FleetGrant.ACCESS_ADMIN), "r", user("alice", "admins-a"));

        assertThat(outcome.status()).isEqualTo("proposed");
        assertThat(outcome.eligibleApproverGroups()).containsExactlyInAnyOrder("admins-a", "admins-b");
    }

    @Test
    void aNullAuthenticationIsRecordedAsUnknownNeverAsBlank() {
        apexGroups("admins-a", "admins-b");

        service("admins-a").submit(GrantChange.ladderRemove("ops", Role.VIEWER, "e1", "t1"), "r", null);

        verify(audit).beginPending(eq("unknown"), any(), any(), any(), any(), any(), any(), anyMap());
    }

    /* ------------------------------ approve ------------------------------ */

    private AccessGrantProposal pending(GrantChange change, String proposer) throws Exception {
        AccessGrantProposal p = new AccessGrantProposal(
                proposer,
                change.group(),
                change.kind(),
                mapper.writeValueAsString(change),
                change.summary(),
                "because",
                NOW.minusSeconds(60),
                NOW.plus(Duration.ofHours(1)));
        when(proposals.findById(7L)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void anIndependentApproverAppliesTheProposalAndAnAccessAdminChangeAlerts() throws Exception {
        apexGroups("admins-a", "admins-b");
        GrantChange change = GrantChange.fleetAdd("ops", FleetGrant.ACCESS_ADMIN);
        AccessGrantProposal p = pending(change, "alice");

        Outcome outcome = service("admins-a").approve(7L, user("bob", "admins-b"));

        assertThat(outcome.status()).isEqualTo("applied");
        verify(store).apply(change);
        verify(audit)
                .beginPending(
                        eq("bob"),
                        any(),
                        any(),
                        eq("ops"),
                        eq("grant-add"),
                        startsWith("four-eyes approval of proposal #7"),
                        any(),
                        anyMap());
        verify(alert).fire(eq("access-admin-grant-change"), anyString());
        assertThat(p.getStatus()).isEqualTo(AccessGrantProposal.Status.APPROVED);
        assertThat(p.getApprover()).isEqualTo("bob");
        verify(proposals).save(p);
    }

    @Test
    void approvalRefusals() throws Exception {
        apexGroups("admins-a", "admins-b");
        AccessMappingAdminService svc = service("admins-a");
        GrantChange change = GrantChange.ladderAdd("ops", Role.OPERATOR, "*", "*");

        when(proposals.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.approve(1L, user("bob"))).hasMessageContaining("no such proposal");

        AccessGrantProposal p = pending(change, "alice");
        assertThatThrownBy(() -> svc.approve(7L, user("alice", "admins-b")))
                .isInstanceOf(IneligibleApproverException.class)
                .hasMessageContaining("own proposal");
        assertThatThrownBy(() -> svc.approve(7L, user("carol", "ops")))
                .isInstanceOf(IneligibleApproverException.class)
                .hasMessageContaining("not independent");

        p.decide(AccessGrantProposal.Status.REJECTED, "dave", NOW);
        assertThatThrownBy(() -> svc.approve(7L, user("bob"))).hasMessageContaining("is REJECTED");
        verify(store, never()).apply(any());
    }

    @Test
    void anExpiredProposalIsMarkedExpiredAndRefused() throws Exception {
        apexGroups("admins-a", "admins-b");
        AccessGrantProposal p = pending(GrantChange.fleetAdd("ops", FleetGrant.REGISTRY_ADMIN), "alice");
        clock = Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC);

        assertThatThrownBy(() -> service("admins-a").approve(7L, user("bob", "admins-b")))
                .hasMessageContaining("has expired");
        assertThat(p.getStatus()).isEqualTo(AccessGrantProposal.Status.EXPIRED);
        verify(proposals).save(p);
        verify(store, never()).apply(any());
    }

    @Test
    void aTamperedProposalFailsItsIntegrityCheck() throws Exception {
        apexGroups("admins-a", "admins-b");
        GrantChange shown = GrantChange.fleetAdd("ops", FleetGrant.REGISTRY_ADMIN);
        GrantChange stored = GrantChange.fleetAdd("ops", FleetGrant.ACCESS_ADMIN);
        AccessGrantProposal p = new AccessGrantProposal(
                "alice",
                "ops",
                shown.kind(),
                mapper.writeValueAsString(stored),
                shown.summary(),
                "r",
                NOW,
                NOW.plusSeconds(60));
        when(proposals.findById(7L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service("admins-a").approve(7L, user("bob", "admins-b")))
                .hasMessageContaining("integrity check");
        verify(store, never()).apply(any());
    }

    @Test
    void anUnreadableProposalIsRefusedNotApplied() {
        apexGroups("admins-a", "admins-b");
        AccessGrantProposal p = new AccessGrantProposal(
                "alice", "ops", GrantChange.Kind.FLEET_ADD, "{not json", "s", "r", NOW, NOW.plusSeconds(60));
        when(proposals.findById(7L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service("admins-a").approve(7L, user("bob", "admins-b")))
                .hasMessageContaining("cannot deserialize");
    }

    @Test
    void pendingProposalsAreReadNewestFirst() {
        List<AccessGrantProposal> rows = List.of(mock(AccessGrantProposal.class));
        when(proposals.findByStatusOrderByCreatedAtDesc("PENDING")).thenReturn(rows);

        assertThat(service("admins-a").pendingProposals()).isSameAs(rows);
    }

    /* ------------------------------ drift ------------------------------ */

    @Test
    void driftReportsBothSidesAndAlertsWhenAFilePinWouldHaveNoApex() {
        when(file.allGrantsByGroup())
                .thenReturn(Map.of(
                        "only-file", List.of(ScopeGrant.global(Role.VIEWER)),
                        "both", List.of(ScopeGrant.global(Role.VIEWER))));
        when(ladderRepo.findAll())
                .thenReturn(List.of(
                        new GroupScopeGrantEntity("both", "VIEWER", "*", "*", "ui", NOW),
                        new GroupScopeGrantEntity("only-db", "VIEWER", "*", "*", "ui", NOW)));

        AccessMappingAdminService.DriftReport withApex = service("admins-a").drift();
        assertThat(withApex.onlyInFile()).containsExactly("only-file");
        assertThat(withApex.onlyInDb()).containsExactly("only-db");
        assertThat(withApex.fileWouldHaveApex()).isTrue();
        verifyNoInteractions(alert);

        AccessMappingAdminService.DriftReport noApex = service(null).drift();
        assertThat(noApex.fileWouldHaveApex()).isFalse();
        verify(alert).fire(eq("mapping-drift-no-file-apex"), anyString());
    }

    @Test
    void typedFailuresCarryTheirMessages() {
        assertThat(new AccessMappingAdminService.NoEligibleApproverException("x"))
                .hasMessage("x");
        assertThat(Set.of(new CrudDisabledException("a").getMessage(), new ApexInvariantException("b").getMessage()))
                .containsExactlyInAnyOrder("a", "b");
    }
}
