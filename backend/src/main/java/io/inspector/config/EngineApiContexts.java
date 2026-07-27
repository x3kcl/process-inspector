package io.inspector.config;

import java.util.Locale;

/**
 * Derives an engine's SIBLING REST contexts — {@code /cmmn-api}, {@code /external-job-api} — from
 * its registered process-API {@code base-url}. One helper so the three places that need them
 * ({@link io.inspector.client.CmmnApiClient}, {@link io.inspector.client.ExternalJobApiClient} and
 * {@link io.inspector.registry.RegistryUrlValidator}'s pinned result) can never disagree about
 * where a given engine keeps its CMMN API.
 *
 * <p>Flowable publishes those APIs BESIDE the process API, not under it, and the segment they sit
 * beside depends on how the engine is deployed:
 *
 * <table>
 *   <tr><th>Deployment</th><th>base-url</th><th>CMMN sibling</th></tr>
 *   <tr>
 *     <td>Standalone {@code flowable-rest} war</td>
 *     <td>{@code …/flowable-rest/service}</td>
 *     <td>{@code …/flowable-rest/cmmn-api}</td>
 *   </tr>
 *   <tr>
 *     <td>Spring Boot starter (embedded engine)</td>
 *     <td>{@code …/process-api}</td>
 *     <td>{@code …/cmmn-api}</td>
 *   </tr>
 * </table>
 *
 * <p>The Boot layout is what an application that EMBEDS Flowable exposes ({@code
 * flowable-spring-boot-starter} maps each engine's REST API onto its own dispatcher: {@code
 * /process-api}, {@code /cmmn-api}, {@code /dmn-api}, {@code /external-job-api}) — the shape of the
 * flap engine on hp02. Before this was recognized, a Boot engine's siblings were derived by the
 * fallback rule below and resolved to {@code …/process-api/cmmn-api}, which 404s: the Case
 * Inspector and external-worker lanes looked capability-less on an engine that in fact has them.
 *
 * <p>Anything else falls back to APPENDING the sibling, which is the only safe guess for a layout
 * this code does not know: a wrong 404 on an optional lane, never a call aimed at some other
 * engine's context. A trailing slash is tolerated ({@code …/process-api/} is the same base as
 * {@code …/process-api}) — it is an easy thing to leave in a hand-written registry entry.
 */
public final class EngineApiContexts {

    /** Base-url suffixes that mark a KNOWN layout, i.e. a segment to replace rather than extend. */
    private static final String[] REPLACEABLE_SUFFIXES = {"/service", "/process-api"};

    private EngineApiContexts() {}

    /** The CMMN Management/Runtime/Repository/History REST context for this engine. */
    public static String cmmnApi(String baseUrl) {
        return sibling(baseUrl, "/cmmn-api");
    }

    /** The External Worker (job) REST context for this engine. */
    public static String externalJobApi(String baseUrl) {
        return sibling(baseUrl, "/external-job-api");
    }

    /**
     * {@code sibling} must start with {@code /}. Suffix matching is case-insensitive on the path
     * segment only — {@code /Process-Api} is the same context — while the returned URL keeps the
     * caller's original casing everywhere it is not replaced.
     */
    public static String sibling(String baseUrl, String sibling) {
        String base = stripTrailingSlash(baseUrl);
        String lower = base.toLowerCase(Locale.ROOT);
        for (String suffix : REPLACEABLE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return base.substring(0, base.length() - suffix.length()) + sibling;
            }
        }
        return base + sibling;
    }

    private static String stripTrailingSlash(String url) {
        return url.length() > 1 && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
