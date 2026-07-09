package io.inspector.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Audit-log policy knobs (R-AUD-07), bound from {@code inspector.audit.*} and validated fail-fast
 * at boot so a mistyped regex or a {@code javascript:} link template never reaches a request:
 *
 * <ul>
 *   <li>{@code ticket-id-pattern} — optional regex a supplied ticketId must match (null = accept
 *       anything non-blank). Validated as a compilable pattern at boot. It is DEPLOY config (not
 *       user input) and runs only against a ≤200-char ticketId — but keep it simple/linear (avoid
 *       nested quantifiers) so a pathological pattern can't backtrack (ReDoS).
 *   <li>{@code ticket-required-on} — {@code none|prod|all}: where a ticketId is mandatory on a
 *       mutation (default {@code none}).
 *   <li>{@code ticket-url-template} — e.g. {@code https://jira.example/browse/&#123;ticketId&#125;};
 *       the SPA linkifies the ticketId cell with it. Validated at boot to be an {@code http(s)} URL
 *       containing exactly one {@code &#123;ticketId&#125;} token (so a {@code javascript:}/{@code data:}
 *       scheme is rejected before it can render).
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "inspector.audit")
public record AuditProperties(String ticketIdPattern, TicketRequiredOn ticketRequiredOn, String ticketUrlTemplate) {

    /** Where a ticketId is mandatory (R-AUD-07: "prod may require"). */
    public enum TicketRequiredOn {
        NONE,
        PROD,
        ALL
    }

    private static final String TOKEN = "{ticketId}";

    public AuditProperties {
        if (ticketIdPattern != null && !ticketIdPattern.isBlank()) {
            try {
                Pattern.compile(ticketIdPattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("inspector.audit.ticket-id-pattern is not a valid regex", e);
            }
        }
        if (ticketUrlTemplate != null && !ticketUrlTemplate.isBlank()) {
            validateTemplate(ticketUrlTemplate);
        }
    }

    public TicketRequiredOn ticketRequiredOnOrDefault() {
        return ticketRequiredOn != null ? ticketRequiredOn : TicketRequiredOn.NONE;
    }

    private static void validateTemplate(String template) {
        int first = template.indexOf(TOKEN);
        if (first < 0 || first != template.lastIndexOf(TOKEN)) {
            throw new IllegalArgumentException(
                    "inspector.audit.ticket-url-template must contain exactly one " + TOKEN + " token");
        }
        // Parse a substituted copy so the scheme is checked with a real URI, not a prefix guess —
        // this is what rejects javascript:/data: (only http/https pass).
        URI uri;
        try {
            uri = new URI(template.replace(TOKEN, "TEST-1"));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("inspector.audit.ticket-url-template is not a valid URL", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("inspector.audit.ticket-url-template must be an http(s) URL");
        }
    }
}
