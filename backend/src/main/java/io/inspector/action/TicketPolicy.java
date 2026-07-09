package io.inspector.action;

import io.inspector.config.AuditProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The ticketId half of the §6 reason ladder (R-AUD-07): server-side validation at the guard door,
 * alongside the reason check. A present-but-malformed ticketId is refused ({@code ticket-id-invalid});
 * an absent one is refused only where {@code ticket-required-on} demands it against the engine's
 * environment. The value is CR/LF-stripped + length-capped on ingest (it is chain-covered,
 * unredacted, 400-day-retained — DATA-CLASSIFICATION §4).
 */
@Component
public class TicketPolicy {

    /** ticketId is a short handle, not a payload — cap it so a pasted blob can't bloat the row. */
    static final int MAX_LENGTH = 200;

    private final AuditProperties props;
    private final Pattern pattern; // null = accept any non-blank ticketId

    public TicketPolicy(AuditProperties props) {
        this.props = props;
        this.pattern =
                (props.ticketIdPattern() != null && !props.ticketIdPattern().isBlank())
                        ? Pattern.compile(props.ticketIdPattern())
                        : null;
    }

    /**
     * Normalize + validate; returns the value to store (or null when absent and not required).
     *
     * @throws GuardRefusedException {@code ticket-id-required} (absent where mandatory) or
     *     {@code ticket-id-invalid} (present but not matching the configured pattern)
     */
    public String validate(String rawTicketId, EngineEnvironment environment) {
        String ticket = normalize(rawTicketId);
        if (ticket == null) {
            if (isRequired(environment)) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "ticket-id-required",
                        "A ticket id is required for actions on this engine. Nothing happened.");
            }
            return null;
        }
        // REFUSE an over-long ticketId rather than silently truncating it (Gemini S3) — truncation
        // could store a value that doesn't match the operator's real ticket, or subvert the pattern.
        if (ticket.length() > MAX_LENGTH) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "ticket-id-invalid",
                    "The ticket id must not exceed " + MAX_LENGTH + " characters. Nothing happened.");
        }
        if (pattern != null && !pattern.matcher(ticket).matches()) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "ticket-id-invalid",
                    "The ticket id does not match the required format. Nothing happened.");
        }
        return ticket;
    }

    private boolean isRequired(EngineEnvironment environment) {
        return switch (props.ticketRequiredOnOrDefault()) {
            case NONE -> false;
            case PROD -> environment == EngineEnvironment.PROD;
            case ALL -> true;
        };
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.replaceAll("[\\r\\n]+", " ").strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
