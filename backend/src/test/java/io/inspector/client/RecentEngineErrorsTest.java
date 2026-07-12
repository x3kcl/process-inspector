package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.api.RequestIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Rung 1 (pure): the bounded recent-engine-errors ring buffer (issue #96, {@code GET /api/diag}).
 * Newest-first ordering, correlationId capture off the recording thread's MDC, and the capacity
 * bound actually evicting the oldest entry rather than growing unbounded.
 */
class RecentEngineErrorsTest {

    private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecentEngineErrors errors = new RecentEngineErrors(clock);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void capturesTheCorrelationIdFromMdcAtRecordTime() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-abc");

        errors.record("engine-a", "INTERACTIVE", new IllegalStateException("boom"));

        List<RecentEngineErrors.Entry> recent = errors.recent(10);
        assertThat(recent).hasSize(1);
        RecentEngineErrors.Entry entry = recent.get(0);
        assertThat(entry.engineId()).isEqualTo("engine-a");
        assertThat(entry.leg()).isEqualTo("INTERACTIVE");
        assertThat(entry.errorClass()).isEqualTo("IllegalStateException");
        assertThat(entry.message()).isEqualTo("boom");
        assertThat(entry.correlationId()).isEqualTo("req-abc");
        assertThat(entry.at()).isEqualTo(NOW);
    }

    @Test
    void noMdcContextYieldsANullCorrelationIdNeverAFabricatedOne() {
        MDC.clear();

        errors.record("engine-a", "INTERACTIVE", new RuntimeException("no correlation"));

        assertThat(errors.recent(10).get(0).correlationId()).isNull();
    }

    @Test
    void newestFirst() {
        errors.record("engine-a", "INTERACTIVE", new RuntimeException("first"));
        errors.record("engine-b", "BACKGROUND", new RuntimeException("second"));

        List<RecentEngineErrors.Entry> recent = errors.recent(10);
        assertThat(recent).extracting(RecentEngineErrors.Entry::message).containsExactly("second", "first");
    }

    @Test
    void recentRespectsTheRequestedLimitEvenWithMoreEntriesAvailable() {
        for (int i = 0; i < 5; i++) {
            errors.record("engine-a", "INTERACTIVE", new RuntimeException("err-" + i));
        }

        assertThat(errors.recent(2)).hasSize(2);
    }

    @Test
    void theCapacityBoundEvictsTheOldestEntryRatherThanGrowingUnbounded() {
        for (int i = 0; i < 60; i++) {
            errors.record("engine-a", "INTERACTIVE", new RuntimeException("err-" + i));
        }

        List<RecentEngineErrors.Entry> recent = errors.recent(100);
        assertThat(recent).hasSize(50); // CAPACITY
        assertThat(recent.get(0).message()).isEqualTo("err-59"); // newest survives
        assertThat(recent.get(recent.size() - 1).message()).isEqualTo("err-10"); // oldest 10 evicted
    }
}
