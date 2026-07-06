package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R-SAFE-12: the group→scope mapping lives in a mounted file, re-read on a ≤60s TTL —
 * a mid-incident grant becomes effective without restart or re-login; a broken edit
 * keeps the previous known-good mapping (fail safe, never lock everyone out).
 */
class ScopeMappingServiceTest {

    @TempDir
    Path dir;

    private final Clock clock = mock(Clock.class);

    private ScopeMappingService serviceFor(Path file) {
        SecurityProperties props = new SecurityProperties(null, file.toString(), 60, null);
        return new ScopeMappingService(props, clock);
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
