package io.inspector.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal in-process flowable-rest stub (JDK {@link HttpServer}, zero new dependency) that
 * answers the SAME paged job-query contract the BFF actually calls, generating rows ON THE FLY
 * — it never materializes a fixture list the size of the simulated dataset (R-TEST-05 P2,
 * TEST-STRATEGY.md §7/§10: "scale that must never be seeded for real ... the 50k-DLQ P2
 * stub"). It fakes DATA VOLUME only, never join/status semantics: every synthetic row is
 * already the shape the real join expects (a {@code bpmn} scopeType, a stable
 * {@code key:version:uuid} processDefinitionId). This is NOT a join-logic mock — the
 * never-mock-Flowable iron rule governs status/paging SEMANTICS, which stay proven at rung 4
 * against REAL flowable-rest elsewhere ({@code SearchServiceIT}, {@code TriageCmmnScopeIT}).
 * The sanctioned exception it exercises is the same one TEST-STRATEGY §10/S1 already names:
 * synthetic wire payloads ARE permitted for scale that must never be seeded on a real engine.
 *
 * <p><b>Reusable foundation.</b> Every management/query endpoint the Stage-0 aggregator and
 * search plans touch answers a fast, honest "nothing here" by default, so the health probe,
 * the RETRYING lanes and the count-only Stage-0 legs never choke on an unimplemented path —
 * only the DEADLETTER lane ({@code GET /management/deadletter-jobs}) serves the configured
 * {@code dlqTotalRows} synthetic total, honoring {@code start}/{@code size} (and paging via a
 * {@code processInstanceIds}-keyed echo for the two {@code POST /query/*} hydration legs). The
 * sibling P1 fan-out suite (issue #298 — 10 stub engines, 3 slow, 2 timing out) can extend this
 * class instead of re-building the boilerplate: {@link #withResponseDelayMs} and
 * {@link #hangForever} are the seams it needs for per-engine latency/timeout injection.
 */
public final class StubFlowableEngine implements AutoCloseable {

    private static final String BASE = "/flowable-rest/service";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    // A delayed response is scheduled, never Thread.sleep-ed (NoSleepInTestsArchTest bans
    // Thread.sleep/TimeUnit.sleep anywhere under src/test/java by CLASS LOCATION, not just in
    // classes with @Test methods — a support class is in scope too) — this is also simply the
    // correct implementation: it doesn't block a handler thread for the delay duration.
    private final ScheduledExecutorService delayScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stub-flowable-engine-delay");
        t.setDaemon(true);
        return t;
    });
    private final long dlqTotalRows;
    private final AtomicInteger deadletterRequestCount = new AtomicInteger();
    private final AtomicInteger totalRequestCount = new AtomicInteger();
    // Race-proof "never an unpaged fetch" evidence: a BACKGROUND-lane health probe (@Scheduled,
    // EngineHealthService) may ALSO hit /management/deadletter-jobs?size=1 concurrently with a
    // test's own scan, so asserting an EXACT request count would be a time bomb. Recording the
    // actual start/size offsets instead lets the test assert the cap boundary directly (a
    // start at the cap edge was reached; none past it; no single request asked for the whole
    // volume) regardless of how many extra size=1 health-probe calls interleave.
    private final Set<Integer> deadletterStartsSeen = ConcurrentHashMap.newKeySet();
    private final AtomicInteger maxDeadletterSizeRequested = new AtomicInteger();
    private volatile int responseDelayMs = 0;
    private volatile boolean hangForever = false;

    /** @param dlqTotalRows the synthetic DEADLETTER lane's reported total (never materialized as a list). */
    public StubFlowableEngine(long dlqTotalRows) {
        this.dlqTotalRows = dlqTotalRows;
        try {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("stub engine failed to bind an ephemeral port", e);
        }
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://localhost:" + port() + BASE;
    }

    /** How many times the DEADLETTER lane was actually paged — the "never an unpaged fetch" proof. */
    public int deadletterRequestCount() {
        return deadletterRequestCount.get();
    }

    public int totalRequestCount() {
        return totalRequestCount.get();
    }

    /** Every distinct {@code start} offset the DEADLETTER lane was paged at. */
    public Set<Integer> deadletterStartsSeen() {
        return Set.copyOf(deadletterStartsSeen);
    }

    /** The largest {@code size} ever requested on the DEADLETTER lane — never the whole volume. */
    public int maxDeadletterSizeRequested() {
        return maxDeadletterSizeRequested.get();
    }

    /** Reusable seam for #298: every subsequent response is delayed this long before answering. */
    public StubFlowableEngine withResponseDelayMs(int delayMs) {
        this.responseDelayMs = delayMs;
        return this;
    }

    /** Reusable seam for #298: the stub stops answering at all (a "2 timing out" fixture). */
    public StubFlowableEngine hangForever() {
        this.hangForever = true;
        return this;
    }

    /**
     * R-TEST-05 P1 (issue #298): flips a degraded stub back to instant, healthy responses — the
     * "flip one stub back to healthy and assert recovery" fixture for the breaker half-open→closed
     * proof. Clears BOTH degraded modes (a stub previously {@link #hangForever() hung} or merely
     * {@link #withResponseDelayMs delayed} both become immediately healthy), so one method covers
     * either starting condition.
     */
    public StubFlowableEngine recoverToHealthy() {
        this.hangForever = false;
        this.responseDelayMs = 0;
        return this;
    }

    @Override
    public void close() {
        delayScheduler.shutdownNow();
        server.stop(0);
    }

    /* ---------------- dispatch ---------------- */

    private void dispatch(HttpExchange exchange) {
        totalRequestCount.incrementAndGet();
        if (hangForever) {
            // Never respond, never close — the client's own read-timeout is what ends this
            // (the do-no-harm contract this fixture exists to exercise, #298).
            return;
        }
        int delay = responseDelayMs;
        if (delay > 0) {
            delayScheduler.schedule(() -> routeSafely(exchange), delay, TimeUnit.MILLISECONDS);
        } else {
            routeSafely(exchange);
        }
    }

    private void routeSafely(HttpExchange exchange) {
        try {
            route(exchange);
        } catch (IOException e) {
            // Best-effort: the client may already have given up (read timeout, cancelled fan-out).
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String rel = path.startsWith(BASE) ? path.substring(BASE.length()) : path;
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        if ("GET".equals(method) && rel.equals("/management/deadletter-jobs")) {
            deadletterRequestCount.incrementAndGet();
            int startParam = parseInt(query.get("start"), 0);
            int sizeParam = parseInt(query.get("size"), 10);
            deadletterStartsSeen.add(startParam);
            maxDeadletterSizeRequested.updateAndGet(prev -> Math.max(prev, sizeParam));
            servePage(exchange, query.get("start"), query.get("size"), dlqTotalRows);
        } else if ("GET".equals(method)
                && (rel.equals("/management/jobs")
                        || rel.equals("/management/timer-jobs")
                        || rel.equals("/management/suspended-jobs"))) {
            servePage(exchange, query.get("start"), query.get("size"), 0);
        } else if ("GET".equals(method) && rel.equals("/management/engine")) {
            writeJson(exchange, 200, Map.of("version", "6.8.0"));
        } else if ("GET".equals(method) && rel.equals("/history/historic-activity-instances")) {
            writeJson(exchange, 200, emptyPage(0));
        } else if ("POST".equals(method)
                && (rel.equals("/query/process-instances") || rel.equals("/query/historic-process-instances"))) {
            serveQueryEcho(exchange);
        } else {
            writeJson(exchange, 404, Map.of("message", "stub: no route for " + method + " " + rel));
        }
    }

    /** GET job-lane page: {@code total} rows exist "on the engine"; only {@code size} are ever materialized. */
    private void servePage(HttpExchange exchange, String startParam, String sizeParam, long total) throws IOException {
        int start = parseInt(startParam, 0);
        int size = parseInt(sizeParam, 10);
        long remaining = Math.max(0, total - start);
        int count = (int) Math.min(size, remaining);
        List<Map<String, Object>> data = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            data.add(syntheticJob(start + i));
        }
        writeJson(exchange, 200, page(data, total, start, size));
    }

    /**
     * The two {@code POST /query/*} legs the BFF drives with an explicit {@code
     * processInstanceIds} id set (hydration + the parent-map walk + the suspended-flags bulk
     * check): echo one synthetic row per requested id, honoring the body's own {@code start}/
     * {@code size}. Every other POST shape (the Stage-0 {@code size:1} total-only counts, the
     * businessKeyLike canary) is a count-only leg this suite doesn't exercise — answer an
     * honest empty page.
     */
    @SuppressWarnings("unchecked")
    private void serveQueryEcho(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonBody(exchange);
        Object rawIds = body.get("processInstanceIds");
        if (!(rawIds instanceof List<?> idsList) || idsList.isEmpty()) {
            writeJson(exchange, 200, emptyPage(0));
            return;
        }
        List<String> ids = (List<String>) idsList;
        int start = intValue(body.get("start"), 0);
        int size = intValue(body.get("size"), ids.size());
        int end = Math.min(ids.size(), Math.max(start, 0) + Math.max(size, 0));
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = Math.max(start, 0); i < end; i++) {
            data.add(syntheticHistoricRow(ids.get(i)));
        }
        writeJson(exchange, 200, page(data, ids.size(), start, size));
    }

    /* ---------------- synthetic rows (generated per-request, never stored) ---------------- */

    private static final String STUB_DEFINITION_ID = "stub-def:1:00000000-0000-0000-0000-0000000000d1";

    private static Map<String, Object> syntheticJob(long index) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "stub-dlq-job-" + index);
        job.put("processInstanceId", "stub-pi-" + index);
        job.put("processDefinitionId", STUB_DEFINITION_ID);
        job.put("scopeType", "bpmn");
        job.put("exceptionMessage", "stub dead-letter failure (synthetic volume fixture)");
        job.put("retries", 0);
        job.put("dueDate", null);
        return job;
    }

    private static Map<String, Object> syntheticHistoricRow(String processInstanceId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", processInstanceId);
        row.put("processDefinitionId", STUB_DEFINITION_ID);
        row.put("startTime", "2026-07-01T00:00:00.000+0000");
        row.put("endTime", null); // still open — consistent with a FAILED (not completed) instance
        row.put("businessKey", null);
        row.put("suspended", false);
        row.put("superProcessInstanceId", null); // flat fixture: no call-activity roll-up in play
        return row;
    }

    private static Map<String, Object> page(List<Map<String, Object>> data, long total, int start, int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("total", total);
        body.put("start", start);
        body.put("size", size);
        return body;
    }

    private static Map<String, Object> emptyPage(int size) {
        return page(List.of(), 0, 0, size);
    }

    /* ---------------- plumbing ---------------- */

    private static Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            byte[] bytes = buf.toByteArray();
            if (bytes.length == 0) {
                return Map.of();
            }
            return MAPPER.readValue(bytes, Map.class);
        }
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }
}
