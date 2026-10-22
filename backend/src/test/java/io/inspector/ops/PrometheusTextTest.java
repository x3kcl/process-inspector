package io.inspector.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrometheusTextTest {

    @Test
    void readsLabeledFlowableFamiliesAndSkipsComments() {
        String text = """
                # HELP flowable_jobs_async_total jobs
                # TYPE flowable_jobs_async_total counter
                flowable_jobs_async_total{type="rejected"} 4
                flowable_jobs_async_total{type="executed"} 90
                flowable_task_executor_active 3
                not_a_sample
                """;

        var samples = PrometheusText.parse(text);

        assertThat(PrometheusText.labeled(samples, "flowable_jobs_async_total", "type", "rejected"))
                .isEqualTo(4.0);
        assertThat(PrometheusText.labeled(samples, "flowable_jobs_async_total", "type", "queued"))
                .isNull();
        assertThat(samples).anyMatch(s -> s.name().equals("flowable_task_executor_active") && s.value() == 3.0);
    }
}
