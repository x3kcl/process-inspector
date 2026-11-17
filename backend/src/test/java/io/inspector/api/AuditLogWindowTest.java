package io.inspector.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditEntryRepository;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ReadScopeGate;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

/**
 * #662: the incident episode row's "open in Ops log" link bounds the log to the episode window
 * ({@code since}…{@code until}) and its engine. {@code until} must reach the query, not be
 * filtered after a capped, newest-first page — later rows would push the episode's rows out.
 */
class AuditLogWindowTest {

    private static final Instant START = Instant.parse("2026-11-14T11:47:11Z");
    private static final Instant END = Instant.parse("2026-11-14T11:52:57Z");

    private final AuditEntryRepository repository = mock(AuditEntryRepository.class);
    private final ReadScopeGate readScope = mock(ReadScopeGate.class);
    private final AuditController controller = new AuditController(repository, mock(RbacAuthorizer.class), readScope);
    private final Authentication auth = mock(Authentication.class);

    @Test
    void untilBoundsTheUnscopedQuery() {
        // Given: read-scope enforcement off.
        when(readScope.readableEngineIds(any())).thenReturn(null);

        // When
        controller.operationsLog(null, null, "engine-a", null, START, END, 100, auth);

        // Then
        verify(repository)
                .findLogWindow(isNull(), isNull(), eq("engine-a"), isNull(), eq(START), eq(END), any(Pageable.class));
    }

    @Test
    void untilBoundsTheScopedQuery() {
        // Given: read-scope enforcement on.
        when(readScope.readableEngineIds(any())).thenReturn(Set.of("engine-a"));

        // When
        controller.operationsLog(null, null, null, null, START, END, 100, auth);

        // Then
        verify(repository)
                .findLogScopedWindow(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(START),
                        eq(END),
                        eq(Set.of("engine-a")),
                        eq(false),
                        any(Pageable.class));
    }
}
