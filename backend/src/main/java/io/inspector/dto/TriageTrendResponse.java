package io.inspector.dto;

import java.util.List;

/**
 * Job-lane trend series for the Stage-0 sparklines (SPEC §4, R-BAU-08). Read from the
 * {@code triage_snapshot} store (v2/M4) — NOT the live engine — so opening the trend view never
 * touches a struggling engine. One {@link Series} per {@code (engineId, lane)} that has any point
 * in the window; a lane with no history yields no series (never a fabricated flat line).
 *
 * @param asOf   the query instant (ISO-8601 UTC)
 * @param window the requested look-back (ISO-8601 duration, echoed for the UI's axis label)
 * @param series ascending-by-time points, grouped by engine + lane
 */
public record TriageTrendResponse(String asOf, String window, List<Series> series) {

    public record Series(String engineId, String lane, List<Point> points) {}

    /** One sampled bucket: the count at an instant. */
    public record Point(String sampledAt, long count) {}
}
