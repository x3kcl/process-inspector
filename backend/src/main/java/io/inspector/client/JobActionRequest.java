package io.inspector.client;

/** The body of a job verb POST. RED stub (#720): the factories carry nothing yet. */
public record JobActionRequest(String action, String dueDate) {

    public static JobActionRequest move() {
        return new JobActionRequest(null, null);
    }

    public static JobActionRequest moveToHistoryJob() {
        return new JobActionRequest(null, null);
    }

    public static JobActionRequest reschedule(String dueDate) {
        return new JobActionRequest(null, null);
    }
}
