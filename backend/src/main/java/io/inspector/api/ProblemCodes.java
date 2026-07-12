package io.inspector.api;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * The machine {@code code} property every {@link org.springframework.http.ProblemDetail} carries
 * (Engine-client-split sibling issue #87 — F4, "one error contract"): a generic response has no
 * domain-specific code to give (unlike {@code GuardRefusedException} and friends, which already
 * carry one), so it falls back to a kebab-case slug of the HTTP status's reason phrase — stable,
 * machine-parseable, and never derived from exception text (which may not be developer-authored).
 */
final class ProblemCodes {

    private ProblemCodes() {}

    static String fromStatus(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String name = known != null ? known.name() : String.valueOf(status.value());
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
