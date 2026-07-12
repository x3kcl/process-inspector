package io.inspector.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

/**
 * OPERATIONS.md §2 (issue #96): {@link RequestIdFilter} binds {@code correlationId} to MDC on the
 * request thread, but MDC is thread-local — a virtual-thread fan-out (search/triage aggregation,
 * leak-views, bulk dispatch, engine health probes, the alert webhook sender) submits work onto a
 * FRESH virtual thread per task via {@link Executors#newVirtualThreadPerTaskExecutor()}, which
 * inherits nothing. Every log line inside that fan-out has silently lost the correlationId ever
 * since {@code RequestIdFilter} shipped — this closes that gap once, at the executor, rather than
 * wrapping every individual {@code supplyAsync}/{@code runAsync} call site by hand.
 *
 * <p>{@link #newVirtualThreadPerTaskExecutor()} is a drop-in replacement: it snapshots {@link
 * MDC#getCopyOfContextMap()} at SUBMIT time (the calling thread, still carrying the request's
 * context) and restores it on the worker thread for the duration of that one task, clearing it
 * afterward so the next task submitted to the SAME executor (a shared, long-lived field — e.g.
 * {@code BulkJobService}'s per-instance executor serving many jobs/requests over its lifetime)
 * never inherits a stale context from an unrelated, already-finished task.
 */
public final class MdcPropagatingExecutors {

    private MdcPropagatingExecutors() {}

    public static ExecutorService newVirtualThreadPerTaskExecutor() {
        return new MdcPropagatingExecutorService(Executors.newVirtualThreadPerTaskExecutor());
    }

    private static final class MdcPropagatingExecutorService extends AbstractExecutorService {

        private final ExecutorService delegate;

        MdcPropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            delegate.execute(() -> runWithContext(context, command));
        }

        private static void runWithContext(Map<String, String> context, Runnable command) {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context != null) {
                MDC.setContextMap(context);
            } else {
                MDC.clear();
            }
            try {
                command.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
