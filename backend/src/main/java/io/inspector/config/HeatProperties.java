package io.inspector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** RED stub (#784). */
@Validated
@ConfigurationProperties(prefix = "inspector.heat")
public record HeatProperties(Integer sampleSize, Integer maxSampleSize) {

    public int sampleSizeOrDefault() {
        return sampleSize != null ? sampleSize : 200;
    }

    public int maxSampleSizeOrDefault() {
        return maxSampleSize != null ? maxSampleSize : 1000;
    }
}
