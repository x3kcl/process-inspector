package io.inspector.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The audited reveal's per-user rate limit (#743, SPEC R-AUD-12): {@code inspector.reveal.max-per-minute}
 * reveals per user in any 60-second window (default 10, 1–1000). The limit keeps the endpoint from
 * dumping every secret of a fleet in a loop; validated fail-fast at boot.
 */
@Validated
@ConfigurationProperties(prefix = "inspector.reveal")
public record RevealProperties(@Min(1) @Max(1000) Integer maxPerMinute) {

    public int maxPerMinuteOrDefault() {
        return maxPerMinute != null ? maxPerMinute : 10;
    }
}
