package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Rung 1 for legal holds: validation, the audited set/release, and fail-closed compensation. */
class LegalHoldServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final LegalHoldService service = new LegalHoldService(jdbc, audit, clock);

    private static final Instant FROM = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2025-02-01T00:00:00Z");

    @Test
    void setInsertsTheHoldAndAuditsItWithTheActingHuman() {
        UUID id = service.set("engine-a", null, FROM, TO, "case 4711 preservation", "alice");

        assertThat(id).isNotNull();
        verify(jdbc).update(contains("INSERT INTO legal_hold"), any(), any(), any(), any(), any(), any(), any(), any());
        verify(audit).recordConfigEvent(eq("audit-legal-hold-set"), eq("alice"), eq(true), any());
    }

    @Test
    void setRejectsAShortReasonBeforeTouchingTheDb() {
        assertThatThrownBy(() -> service.set(null, null, FROM, TO, "too short", "alice"))
                .isInstanceOf(ResponseStatusException.class);
        verify(jdbc, never()).update(anyString(), (Object[]) any());
        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void setRejectsAnInvertedWindow() {
        assertThatThrownBy(() -> service.set(null, null, TO, FROM, "valid ten char reason", "alice"))
                .isInstanceOf(ResponseStatusException.class);
        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void setCompensatesTheInsertWhenTheAuditEventFails() {
        when(audit.recordConfigEvent(eq("audit-legal-hold-set"), anyString(), anyBoolean(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("audit db down")));

        assertThatThrownBy(() -> service.set(null, null, FROM, TO, "case 4711 preservation", "alice"))
                .isInstanceOf(AuditUnavailableException.class);
        // Fail-closed: the just-inserted hold is deleted so it never takes effect unaudited.
        verify(jdbc).update(contains("DELETE FROM legal_hold"), any(Object.class));
    }

    @Test
    void releaseUpdatesAndAuditsAnActiveHold() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(contains("UPDATE legal_hold SET released_at"), any(), any(), any()))
                .thenReturn(1);

        service.release(id, "alice");

        verify(audit).recordConfigEvent(eq("audit-legal-hold-release"), eq("alice"), eq(true), any());
    }

    @Test
    void releaseOfAnUnknownOrAlreadyReleasedHoldIs404() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(contains("UPDATE legal_hold SET released_at"), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.release(id, "alice")).isInstanceOf(ResponseStatusException.class);
        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void releaseRestoresTheHoldWhenTheAuditEventFails() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(contains("UPDATE legal_hold SET released_at"), any(), any(), any()))
                .thenReturn(1);
        when(audit.recordConfigEvent(eq("audit-legal-hold-release"), anyString(), anyBoolean(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("audit db down")));

        assertThatThrownBy(() -> service.release(id, "alice")).isInstanceOf(AuditUnavailableException.class);
        // Fail-closed: re-activate the hold (protective) since the release could not be audited.
        verify(jdbc).update(contains("released_at = NULL"), any(Object.class));
    }
}
