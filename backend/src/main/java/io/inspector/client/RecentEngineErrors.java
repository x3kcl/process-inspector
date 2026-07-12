package io.inspector.client;

import io.inspector.api.RequestIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * A bounded, in-memory ring buffer of the last N {@link GuardedCaller} failures (issue #96,
 * {@code GET /api/diag}) — the operator-facing "what's been going wrong lately" view, each entry
 * carrying the SAME {@code correlationId} {@link RequestIdFilter} bound to the failing request's
 * log lines and audit row, so a diag snapshot cross-references directly. Process-local, reset on
 * restart — a durable error log already exists (the log stream itself); this is a fast, no-query
 * recency window for the diagnostic endpoint, not a second source of truth.
 */
@Component
public class RecentEngineErrors {

    /** Small and fixed — this is a "what just happened" glance, not an audit trail. */
    private static final int CAPACITY = 50;

    /**
     * A deserialization failure (e.g. {@code HttpMessageNotReadableException} on a malformed or
     * wire-shape-drifted engine response) can embed a snippet of the offending response body in
     * {@code getMessage()} — unlike {@code HttpStatusCodeException}, which is just "{@code <status>
     * <reasonPhrase>}". Bounded defensively, same spirit as {@link
     * io.inspector.audit.AuditService}'s snippet truncation, but far smaller: this is a glance, not
     * a payload viewer.
     */
    private static final int MESSAGE_MAX_CHARS = 500;

    private final Clock clock;
    private final ConcurrentLinkedDeque<Entry> recent = new ConcurrentLinkedDeque<>();

    public RecentEngineErrors(Clock clock) {
        this.clock = clock;
    }

    public record Entry(
            Instant at, String engineId, String leg, String errorClass, String message, String correlationId) {}

    public void record(String engineId, String leg, Throwable error) {
        recent.addFirst(new Entry(
                clock.instant(),
                engineId,
                leg,
                error.getClass().getSimpleName(),
                truncate(error.getMessage()),
                MDC.get(RequestIdFilter.MDC_KEY)));
        while (recent.size() > CAPACITY) {
            recent.removeLast();
        }
    }

    /** Newest first, capped at {@code limit} (never more than {@link #CAPACITY} exist anyway). */
    public List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>(Math.min(limit, CAPACITY));
        for (Entry e : recent) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e);
        }
        return out;
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MESSAGE_MAX_CHARS) {
            return message;
        }
        return message.substring(0, MESSAGE_MAX_CHARS) + "…";
    }
}
