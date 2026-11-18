package io.inspector.heat;

import io.inspector.client.ActivityInstanceRecord;
import io.inspector.surgery.BpmnStructure.Flow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which rows of ONE instance are catch events an event-based gateway armed but that did not fire
 * (owner decision A, #783). Flowable records such an event as a finished row with a duration and
 * nothing that says "not taken" (recorded on 6.3.1, 6.8.0, 7.1.0), so the BPMN decides:
 *
 * <ul>
 *   <li>Instance with route history (any {@code sequenceFlow} row): a row is taken iff one of its
 *       outgoing flows has a row.
 *   <li>Instance without (6.3): taken iff a direct successor has a row starting at or after the
 *       event row's end — an inference, which the caller reports.
 * </ul>
 *
 * Each successor row stands for at most one event row, across all catch events (a merge after
 * two branches is one row): the latest-ending one that ended at or before it starts and that no
 * row of the same successor already stands for. So in a loop the visit that fired is the one
 * attributed, and an event forking into two flows is one visit. An unfinished event row never
 * fired, so it is armed. Every catch event passed has an outgoing flow (see the caller).
 */
final class ArmedCatchEvents {

    private static final String FLOW = "sequenceFlow";

    private ArmedCatchEvents() {}

    /** The armed-not-taken rows (identity set); {@code inferred[0]} is set when the 6.3 rule decided. */
    static Set<ActivityInstanceRecord> of(
            List<ActivityInstanceRecord> rows, Map<String, List<Flow>> catches, boolean[] inferred) {
        Set<ActivityInstanceRecord> armed = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean routes = rows.stream().anyMatch(r -> FLOW.equals(r.activityType()));
        Map<String, List<ActivityInstanceRecord>> eventRows = new HashMap<>();
        Map<String, List<String>> before = new HashMap<>(); // successor (flow id or node id) -> its events
        catches.forEach((event, flows) -> {
            List<ActivityInstanceRecord> own =
                    rows.stream().filter(r -> event.equals(r.activityId())).toList();
            if (own.isEmpty()) {
                return;
            }
            eventRows.put(event, own);
            for (Flow f : flows) {
                before.computeIfAbsent(routes ? f.id() : f.target(), k -> new ArrayList<>())
                        .add(event);
            }
        });
        if (eventRows.isEmpty()) {
            return armed;
        }
        inferred[0] |= !routes;
        Map<ActivityInstanceRecord, Set<String>> claims = new IdentityHashMap<>();
        List<ActivityInstanceRecord> successors = rows.stream()
                .filter(r -> before.containsKey(r.activityId()) && FLOW.equals(r.activityType()) == routes)
                .sorted(Comparator.comparing(r -> orMin(time(r.startTime()))))
                .toList();
        for (ActivityInstanceRecord s : successors) {
            Instant start = orMin(time(s.startTime()));
            candidates(before.get(s.activityId()), eventRows)
                    .filter(ev -> !claimedBy(claims, ev, s.activityId()) && !start.isBefore(time(ev.endTime())))
                    .max(Comparator.comparing(ev -> time(ev.endTime())))
                    .ifPresent(ev -> claim(claims, ev, s.activityId()));
        }
        if (routes) {
            // The flow row is the record; timing only attributes it. Never fewer visits than rows.
            before.forEach((flowId, events) -> {
                long rowsOfFlow = successors.stream()
                        .filter(r -> flowId.equals(r.activityId()))
                        .count();
                List<ActivityInstanceRecord> finished = candidates(events, eventRows)
                        .sorted(Comparator.comparing((ActivityInstanceRecord ev) -> time(ev.endTime()))
                                .reversed())
                        .toList();
                long have = finished.stream()
                        .filter(ev -> claimedBy(claims, ev, flowId))
                        .count();
                long want = Math.min(rowsOfFlow, finished.size());
                for (ActivityInstanceRecord ev : finished) {
                    if (have >= want) {
                        break;
                    }
                    if (!claimedBy(claims, ev, flowId)) {
                        claim(claims, ev, flowId);
                        have++;
                    }
                }
            });
        }
        eventRows.values().stream()
                .flatMap(List::stream)
                .filter(ev -> !claims.containsKey(ev))
                .forEach(armed::add);
        return armed;
    }

    /** The finished rows of {@code events}. */
    private static Stream<ActivityInstanceRecord> candidates(
            List<String> events, Map<String, List<ActivityInstanceRecord>> eventRows) {
        return events.stream()
                .distinct()
                .flatMap(e -> eventRows.get(e).stream())
                .filter(ev -> time(ev.endTime()) != null);
    }

    private static boolean claimedBy(
            Map<ActivityInstanceRecord, Set<String>> claims, ActivityInstanceRecord ev, String successor) {
        return claims.getOrDefault(ev, Set.of()).contains(successor);
    }

    private static void claim(
            Map<ActivityInstanceRecord, Set<String>> claims, ActivityInstanceRecord ev, String successor) {
        claims.computeIfAbsent(ev, k -> new HashSet<>()).add(successor);
    }

    static Instant time(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Instant orMin(Instant t) {
        return t == null ? Instant.MIN : t;
    }
}
