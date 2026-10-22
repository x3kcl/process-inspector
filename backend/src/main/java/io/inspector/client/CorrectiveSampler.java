package io.inspector.client;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Replaces Boot's {@code otelSampler} when tracing is on. Reads stay on the ratio sample.
 * {@code engine.corrective} is recorded even when that ratio drops the parent (#565).
 */
public final class CorrectiveSampler implements Sampler {

    static final String READ_SPAN = "engine.call";
    static final String CORRECTIVE_SPAN = "engine.corrective";

    private final Sampler delegate;

    public CorrectiveSampler(double probability) {
        this.delegate = Sampler.parentBased(Sampler.traceIdRatioBased(probability));
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind spanKind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        if (CORRECTIVE_SPAN.equals(name)) {
            return SamplingResult.recordAndSample();
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return "CorrectiveSampler";
    }
}

/** Boot's {@code otelSampler} is {@code @ConditionalOnMissingBean}, so this bean wins. */
@Configuration
@ConditionalOnProperty(prefix = "management.tracing", name = "enabled", havingValue = "true")
class CorrectiveSamplerConfig {

    @Bean
    Sampler otelSampler(@Value("${management.tracing.sampling.probability:0.01}") double probability) {
        return new CorrectiveSampler(probability);
    }
}
