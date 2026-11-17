package io.inspector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** #743 stub — the reveal rate limit. */
@ConfigurationProperties(prefix = "inspector.reveal")
public record RevealProperties(Integer maxPerMinute) {

    public int maxPerMinuteOrDefault() {
        return 0; // #743 stub
    }
}
