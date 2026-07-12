package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Rung 1 (pure): the MDC-propagation contract (issue #96, OPERATIONS.md §2) that every
 * virtual-thread fan-out site relies on — the calling thread's MDC context is visible inside a
 * submitted task, restored (not leaked) afterward, and the executor's OWN worker context is never
 * polluted between unrelated tasks.
 */
class MdcPropagatingExecutorsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aSubmittedTaskSeesTheCallingThreadsMdcContext() throws Exception {
        MDC.put("correlationId", "req-123");
        ExecutorService executor = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
        CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();

        executor.execute(() -> seen.add(MDC.get("correlationId")));

        await().atMost(2, TimeUnit.SECONDS).until(() -> !seen.isEmpty());
        assertThat(seen).containsExactly("req-123");
        executor.shutdown();
    }

    @Test
    void noContextOnTheCallingThreadMeansNoContextOnTheWorkerEither() throws Exception {
        MDC.clear();
        ExecutorService executor = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
        CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();

        executor.execute(() -> seen.add(MDC.get("correlationId")));

        await().atMost(2, TimeUnit.SECONDS).until(() -> !seen.isEmpty());
        assertThat(seen).containsOnlyNulls();
        executor.shutdown();
    }

    @Test
    void twoTasksSubmittedWithDifferentContextsNeverCrossContaminate() throws Exception {
        // The same executor is a shared, long-lived field in production (BulkJobService serves
        // many requests over its lifetime) — a stale context leaking from task A into task B's
        // worker would misattribute B's log lines to A's request.
        ExecutorService executor = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
        CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();

        MDC.put("correlationId", "req-A");
        executor.submit(() -> {
                    seen.add(MDC.get("correlationId"));
                    return null;
                })
                .get(2, TimeUnit.SECONDS);

        MDC.put("correlationId", "req-B");
        executor.submit(() -> {
                    seen.add(MDC.get("correlationId"));
                    return null;
                })
                .get(2, TimeUnit.SECONDS);

        assertThat(seen).containsExactly("req-A", "req-B");
        executor.shutdown();
    }

    @Test
    void theCallingThreadsOwnContextIsUntouchedAfterSubmitting() throws Exception {
        MDC.put("correlationId", "req-caller");
        ExecutorService executor = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();

        executor.submit(() -> {
                    MDC.put("correlationId", "req-worker-overwrite");
                    return null;
                })
                .get(2, TimeUnit.SECONDS);

        assertThat(MDC.get("correlationId")).isEqualTo("req-caller");
        executor.shutdown();
    }
}
