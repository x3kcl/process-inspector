package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The external-worker-job facade (Engine-client split, #86 — F2/F9): the read-only list against the
 * {@code /external-job-api} sibling context. Takes {@link CallPriority} explicitly — the old
 * god-class hardcoded {@code INTERACTIVE} here with no escape hatch, the other call site the split
 * issue named directly (alongside CMMN).
 */
@Component
public class ExternalJobApiClient {

    private final GuardedCaller guarded;

    public ExternalJobApiClient(GuardedCaller guarded) {
        this.guarded = guarded;
    }

    /**
     * The External Worker REST API lives beside the management API, not under it: the engine's
     * {@code base-url} ends in {@code /service}; the external-job-api is its {@code
     * /external-job-api} sibling. Derived by convention (no extra config); a non-standard
     * deployment would need an explicit override, not offered in v1.x.
     */
    static String externalJobApiBase(EngineConfig engine) {
        String base = engine.baseUrl();
        return base.endsWith("/service")
                ? base.substring(0, base.length() - "/service".length()) + "/external-job-api"
                : base + "/external-job-api";
    }

    /**
     * External-worker jobs (v1.x #7) — Flowable's FIFTH queue, exposed NOT by the management
     * API but by the External Worker REST API at a sibling context (verified live: the
     * {@code /management/*} lanes have no external-worker endpoint; {@code
     * …/external-job-api/jobs} is the read-only list — 200 on 6.8/7.x, 404 on pre-6.8). This
     * is the LIST endpoint only; the {@code acquire} endpoint of that same API would LOCK the
     * job (stealing it from a real worker), so it is never called. Callers must capability-gate
     * (≥ 6.8) first — on an older engine this base 404s.
     */
    public FlowablePage listExternalWorkerJobs(
            EngineConfig engine, CallPriority priority, Map<String, String> filters, int start, int size) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(externalJobApiBase(engine) + "/jobs")
                .queryParam("start", start)
                .queryParam("size", size);
        filters.forEach(b::queryParam);
        java.net.URI uri = b.build().toUri();
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
    }
}
