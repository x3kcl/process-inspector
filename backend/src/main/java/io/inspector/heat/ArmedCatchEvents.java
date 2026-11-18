package io.inspector.heat;

import io.inspector.client.ActivityInstanceRecord;
import io.inspector.surgery.BpmnStructure.Flow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * Each successor row takes at most one event row: the latest-ending one that ended at or before
 * it starts, so in a loop the duration of the visit that fired is attributed, not the wait of
 * the one that was only armed. An unfinished event row never fired, so it is armed.
 */
final class ArmedCatchEvents {

    private static final String FLOW = "sequenceFlow";

    private ArmedCatchEvents() {}

    /** The armed-not-taken rows (identity set); {@code inferred[0]} is set when the 6.3 rule decided. */
    static Set<ActivityInstanceRecord> of(
            List<ActivityInstanceRecord> rows, Map<String, List<Flow>> catches, boolean[] inferred) {
        Set<ActivityInstanceRecord> armed = Collections.newSetFromMap(new IdentityHashMap<>());
        if (catches.isEmpty()) {
            return armed;
        }
        boolean routes = rows.stream().anyMatch(r -> FLOW.equals(r.activityType()));
        for (Map.Entry<String, List<Flow>> e : catches.entrySet()) {
            List<ActivityInstanceRecord> events =
                    rows.stream().filter(r -> e.getKey().equals(r.activityId())).toList();
            if (events.isEmpty()) {
                continue;
            }
            inferred[0] |= !routes;
            Set<String> next =
                    e.getValue().stream().map(f -> routes ? f.id() : f.target()).collect(Collectors.toSet());
            List<ActivityInstanceRecord> successors = rows.stream()
                    .filter(r -> next.contains(r.activityId()) && FLOW.equals(r.activityType()) == routes)
                    .sorted(Comparator.comparing(r -> orMin(time(r.startTime()))))
                    .toList();
            Set<ActivityInstanceRecord> taken = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ActivityInstanceRecord s : successors) {
                Instant start = orMin(time(s.startTime()));
                events.stream()
                        .filter(ev -> !taken.contains(ev)
                                && time(ev.endTime()) != null
                                && !start.isBefore(time(ev.endTime())))
                        .max(Comparator.comparing(ev -> time(ev.endTime())))
                        .ifPresent(taken::add);
            }
            if (routes) {
                // The flow row is the record; timing only attributes it. Never fewer taken than flows.
                int want = Math.min(successors.size(), (int)
                        events.stream().filter(ev -> time(ev.endTime()) != null).count());
                events.stream()
                        .filter(ev -> !taken.contains(ev) && time(ev.endTime()) != null)
                        .sorted(Comparator.comparing((ActivityInstanceRecord ev) -> time(ev.endTime()))
                                .reversed())
                        .limit(Math.max(0, want - taken.size()))
                        .forEach(taken::add);
            }
            events.stream().filter(ev -> !taken.contains(ev)).forEach(armed::add);
        }
        return armed;
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
