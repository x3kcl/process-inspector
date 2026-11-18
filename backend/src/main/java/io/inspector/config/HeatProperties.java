package io.inspector.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Definition heat sample bounds (#784, DEFINITION-HEATMAP.md §3.3, owner decision 1 on #671): the
 * newest {@code sample-size} finished instances by default (200); a request may ask for another N,
 * clamped to {@code [1, max-sample-size]} (1 000). Both 1-1000 and the default at or under the
 * clamp; refused at boot otherwise. Configuration may lower the clamp, never raise it.
 */
@Validated
@ConfigurationProperties(prefix = "inspector.heat")
public record HeatProperties(
        @Min(1) @Max(1000) Integer sampleSize,
        @Min(1) @Max(1000) Integer maxSampleSize) {

    public int sampleSizeOrDefault() {
        return sampleSize != null ? sampleSize : 200;
    }

    public int maxSampleSizeOrDefault() {
        return maxSampleSize != null ? maxSampleSize : 1000;
    }

    @AssertTrue(message = "inspector.heat.sample-size must not exceed max-sample-size")
    public boolean isSampleSizeWithinClamp() {
        return sampleSizeOrDefault() <= maxSampleSizeOrDefault();
    }

    /** {@code requested} clamped to {@code [1, max-sample-size]}; null takes the default. */
    public int clamp(Integer requested) {
        int n = requested != null ? requested : sampleSizeOrDefault();
        return Math.max(1, Math.min(n, maxSampleSizeOrDefault()));
    }
}
