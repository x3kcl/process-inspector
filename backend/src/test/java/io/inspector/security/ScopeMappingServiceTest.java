package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * R-SAFE-12: the group→scope mapping lives in a mounted file, re-read on a ≤60s TTL —
 * a mid-incident grant becomes effective without restart or re-login; a broken edit
 * keeps the previous known-good mapping (fail safe, never lock everyone out).
 */
class ScopeMappingServiceTest {

    @TempDir
    Path dir;

    private final Clock clock = mock(Clock.class);
    private final AuditService audit = mock(AuditService.class);

    private ScopeMappingService serviceFor(Path file) {
        SecurityProperties props = new SecurityProperties(null, file.toString(), 60, null, null, null, null);
        return new ScopeMappingService(props, clock, audit);
    }

    @Test
    void grantsResolveFromTheMountedFileAndReloadAfterTtl() throws IOException {
        Path file = dir.resolve("scopes.yml");
        Files.writeString(file, """
                groups:
                  l1-oncall:
                    - role: RESPONDER
                      engine-id: orders-prod
                """);
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:00:00Z"));
        ScopeMappingService service = serviceFor(file);

        Set<ScopeGrant> grants = service.grantsForGroups(List.of("l1-oncall"));
        assertThat(grants).containsExactly(new ScopeGrant(Role.RESPONDER, "orders-prod", "*"));

        // mid-incident grant: edit the file, stay inside the TTL → old mapping still served
        Files.writeString(file, """
                groups:
                  l1-oncall:
                    - role: OPERATOR
                      engine-id: orders-prod
                """);
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:00:30Z"));
        assertThat(service.grantsForGroups(List.of("l1-oncall")))
                .containsExactly(new ScopeGrant(Role.RESPONDER, "orders-prod", "*"));

        // …cross the TTL → the new grant is live, no restart, no re-login
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:01:01Z"));
        assertThat(service.grantsForGroups(List.of("l1-oncall")))
                .containsExactly(new ScopeGrant(Role.OPERATOR, "orders-prod", "*"));
    }

    @Test
    void brokenEditKeepsThePreviousKnownGoodMapping() throws IOException {
        Path file = dir.resolve("scopes.yml");
        Files.writeString(file, """
                groups:
                  admins:
                    - role: ADMIN
                """);
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:00:00Z"));
        ScopeMappingService service = serviceFor(file);
        assertThat(service.rolesForGroups(List.of("admins"))).containsExactly(Role.ADMIN);

        Files.writeString(file, "groups: [this is not a mapping");
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:02:00Z"));
        assertThat(service.rolesForGroups(List.of("admins"))).containsExactly(Role.ADMIN);
    }

    @Test
    void aContentChangeRecordsAConfigEventWithHashesAndCounts() throws IOException {
        Path file = dir.resolve("scopes.yml");
        Files.writeString(file, """
                groups:
                  team:
                    - role: RESPONDER
                      engine-id: orders-prod
                """);
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:00:00Z"));
        ScopeMappingService service = serviceFor(file);

        service.rolesForGroups(List.of("team")); // boot baseline load = a change from empty

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        // R-AUD-10: actor is "system" (not the tripping user); the boot baseline has null prev-hash.
        verify(audit).recordConfigEvent(eq("config-scope-mapping-reload"), eq("system"), eq(true), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("previousSha256", null)
                .containsEntry("groupCount", 1)
                .containsEntry("grantCount", 1)
                .containsKeys("file", "sha256");
    }

    @Test
    void failsToPreviousWhenTheConfigEventCannotBeRecorded() throws IOException {
        Path file = dir.resolve("scopes.yml");
        Files.writeString(file, """
                groups:
                  team:
                    - role: RESPONDER
                      engine-id: orders-prod
                """);
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:00:00Z"));
        ScopeMappingService service = serviceFor(file);
        assertThat(service.rolesForGroups(List.of("team"))).containsExactly(Role.RESPONDER); // baseline adopted

        // The mapping is edited to grant ADMIN, but the audit store is DOWN for this reload.
        Files.writeString(file, """
                groups:
                  team:
                    - role: ADMIN
                      engine-id: orders-prod
                """);
        // doThrow/doReturn (not when().thenX) so re-stubbing never invokes the throwing mock.
        doThrow(new RuntimeException("audit down")).when(audit).recordConfigEvent(any(), any(), anyBoolean(), any());
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:01:01Z"));
        // fail-to-previous: the unauditable ADMIN grant does NOT go live.
        assertThat(service.rolesForGroups(List.of("team"))).containsExactly(Role.RESPONDER);

        // The audit store recovers; the next TTL retries and now adopts (the hash was rolled back).
        doReturn(null).when(audit).recordConfigEvent(any(), any(), anyBoolean(), any());
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:02:02Z"));
        assertThat(service.rolesForGroups(List.of("team"))).containsExactly(Role.ADMIN);
    }

    @Test
    void aBrokenReloadRecordsAFailedConfigEventAndKeepsPrevious() throws IOException {
        Path file = dir.resolve("scopes.yml");
        Files.writeString(file, """
                groups:
                  admins:
                    - role: ADMIN
                """);
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:00:00Z"));
        ScopeMappingService service = serviceFor(file);
        assertThat(service.rolesForGroups(List.of("admins"))).containsExactly(Role.ADMIN);

        Files.writeString(file, "groups: [this is not a mapping");
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:01:01Z"));
        assertThat(service.rolesForGroups(List.of("admins"))).containsExactly(Role.ADMIN); // previous kept

        // The broken reload is on the ledger as a FAILED config event (succeeded=false).
        verify(audit).recordConfigEvent(eq("config-scope-mapping-reload"), eq("system"), eq(false), any());
    }

    @Test
    void missingFileMeansNoGrantsNotAnError() {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-06T12:00:00Z"));
        ScopeMappingService service = serviceFor(dir.resolve("absent.yml"));
        assertThat(service.grantsForGroups(List.of("anything"))).isEmpty();
    }

    @Test
    void scopedGrantCoverage() {
        ScopeGrant scoped = new ScopeGrant(Role.ADMIN, "orders-prod", "tenant-a");
        assertThat(scoped.covers(Role.OPERATOR, "orders-prod", "tenant-a")).isTrue();
        assertThat(scoped.covers(Role.ADMIN, "orders-prod", "tenant-b")).isFalse(); // other tenant
        assertThat(scoped.covers(Role.ADMIN, "billing-prod", "tenant-a")).isFalse(); // other engine
        assertThat(scoped.covers(Role.ADMIN, "orders-prod", null)).isFalse(); // unpinned engine ≠ tenant grant

        ScopeGrant global = ScopeGrant.global(Role.RESPONDER);
        assertThat(global.covers(Role.VIEWER, "any-engine", null)).isTrue();
        assertThat(global.covers(Role.OPERATOR, "any-engine", null)).isFalse(); // no escalation
    }
}
