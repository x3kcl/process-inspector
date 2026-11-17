package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The body of a job verb POST — {@code /management/{lane}-jobs/{id}} and the CMMN sibling (#720).
 * {@code dueDate} is OMITTED when null: only {@code reschedule} (7.x) carries it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobActionRequest(String action, String dueDate) {

    /** Dead-letter → executable (retry), or timer → executable (fire now). */
    public static JobActionRequest move() {
        return new JobActionRequest("move", null);
    }

    public static JobActionRequest moveToHistoryJob() {
        return new JobActionRequest("moveToHistoryJob", null);
    }

    /** A null due date is refused here, before any engine byte — as the map body did. */
    public static JobActionRequest reschedule(String dueDate) {
        return new JobActionRequest("reschedule", Objects.requireNonNull(dueDate, "dueDate"));
    }
}
