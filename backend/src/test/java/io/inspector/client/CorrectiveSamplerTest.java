package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** #565: a corrective span is sampled when the 1% ratio drops a plain read of the same trace. */
class CorrectiveSamplerTest {

    @Test
    void correctiveSpanIsSampledWhenTheReadIsDropped() {
        CorrectiveSampler sampler = new CorrectiveSampler(0.01);
        Attributes verb = Attributes.of(AttributeKey.stringKey("verb"), "delete-instance");
        // Ratio uses the last 8 bytes. A small value is inside the 1% bound; Long.MAX is not.
        String sampled = "00000000000000000000000000000001";
        String dropped = "00000000000000007fffffffffffffff";

        assertThat(decision(sampler, sampled, CorrectiveSampler.READ_SPAN, Attributes.empty()))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(decision(sampler, dropped, CorrectiveSampler.READ_SPAN, Attributes.empty()))
                .isEqualTo(SamplingDecision.DROP);
        assertThat(decision(sampler, dropped, CorrectiveSampler.READ_SPAN, verb))
                .isEqualTo(SamplingDecision.DROP);
        assertThat(decision(sampler, dropped, CorrectiveSampler.CORRECTIVE_SPAN, Attributes.empty()))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void tracingOnRegistersTheCorrectiveSampler() {
        new ApplicationContextRunner()
                .withPropertyValues("management.tracing.enabled=true")
                .withUserConfiguration(CorrectiveSamplerConfig.class)
                .run(ctx -> assertThat(ctx)
                        .hasSingleBean(Sampler.class)
                        .getBean(Sampler.class)
                        .isInstanceOf(CorrectiveSampler.class));
    }

    @Test
    void tracingOffLeavesSamplingToBoot() {
        new ApplicationContextRunner()
                .withUserConfiguration(CorrectiveSamplerConfig.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(Sampler.class));
    }

    private static SamplingDecision decision(
            CorrectiveSampler sampler, String traceId, String name, Attributes attributes) {
        SamplingResult result =
                sampler.shouldSample(Context.root(), traceId, name, SpanKind.INTERNAL, attributes, List.of());
        return result.getDecision();
    }
}
