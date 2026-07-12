package io.inspector.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A thread-local capture of the raw Flowable REST calls a single derivation makes — the
 * per-leg evidence behind "Explain this status" (R-L3-01, SPEC §3). {@link GuardedCaller}
 * writes one {@link Leg} per outbound call while a recording is {@linkplain #begin() active};
 * everything else is inert (the interceptor short-circuits on {@link #isActive()}), so the
 * capture costs nothing on the hot search/vitals paths.
 *
 * <p>Honesty (R-L3-01): the recorder captures what the engine call WAS — method, URL, request
 * body, HTTP status, wall duration, and the instant the response was observed — as the calls
 * are RE-DERIVED on demand. The inspector never retains the original response bytes, so a
 * recording is always a fresh derivation, labeled as such by the caller.
 *
 * <p>Single-thread by construction: per-instance status derivation runs sequentially on the
 * request thread (no fan-out), so one thread-local list captures the whole leg sequence in
 * call order. Always paired {@link #begin()}…{@link #end()} in a try/finally so a thrown leg
 * (a 404 anchor, an open breaker) never leaks the binding onto a pooled thread.
 */
public final class EngineCallRecorder {

    /** One captured engine call. {@code status} is null when the call never got an HTTP reply
     *  (transport error, open circuit breaker) — an honest "no response" rather than a fake 0. */
    public record Leg(String method, String url, String requestBody, Integer status, long durationMs, String asOf) {}

    private static final ThreadLocal<List<Leg>> LEGS = new ThreadLocal<>();

    private EngineCallRecorder() {}

    /** Start capturing on THIS thread. Discards any prior (unclosed) recording defensively. */
    public static void begin() {
        LEGS.set(new ArrayList<>());
    }

    /** Whether a recording is active on this thread — the interceptor's fast opt-out. */
    public static boolean isActive() {
        return LEGS.get() != null;
    }

    /** Record one leg, if active. {@code asOf} is stamped now — the moment the reply was seen. */
    public static void record(String method, String url, String requestBody, Integer status, long durationMs) {
        List<Leg> legs = LEGS.get();
        if (legs != null) {
            legs.add(new Leg(
                    method, url, requestBody, status, durationMs, Instant.now().toString()));
        }
    }

    /** Stop capturing and return the legs in call order (empty if never started). */
    public static List<Leg> end() {
        List<Leg> legs = LEGS.get();
        LEGS.remove();
        return legs == null ? List.of() : List.copyOf(legs);
    }
}
