package io.inspector.views;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * The scope-of-CONTENT ↔ scope-of-GOVERNANCE binding for shared views (SHARED-VIEWS.md §4.2, W1 —
 * the fatal flaw the panel + Gemini surfaced). A shared view's declared governance scope
 * ({@code scope_engine_id}/{@code scope_tenant_id}) must NOT be authored free-hand: it is DERIVED
 * from the engines the view's own canonical {@code search} string actually queries, and a publish is
 * refused (S3) if a declared scope fails to {@link #contains} that content. Otherwise a per-engine
 * OPERATOR could publish an "engine-A"-labelled canon whose search quietly targets prod — the label
 * a lie, and the stored query text a business-key leak.
 *
 * <p>Pure + registry-agnostic: the engine→tenant lookup is a {@link Function} seam (the service
 * supplies it from {@code EngineRegistry}), so every rule here is rung-1 testable.
 *
 * <p>The {@code search} string is the M2b canonical URL query string; engines ride the {@code engines}
 * key as a comma-joined list (see the frontend {@code urlState} codec). An ABSENT/empty {@code engines}
 * key means "all enabled engines" (SearchRequest semantics) — the widest possible content, coverable
 * only by the global {@code '*'/'*'} scope.
 */
public final class SharedViewScope {

    /** The global wildcard, shared with {@code ScopeGrant.ANY} / {@code SharedView.ANY}. */
    public static final String ANY = SharedView.ANY;

    /** The URL query key carrying the engine id list (frontend {@code urlState} codec). */
    private static final String ENGINES_KEY = "engines";

    private static final String LIST_SEP = ",";

    private SharedViewScope() {}

    /** The tightest single scope tuple (engineId, tenantId). */
    public record Scope(String engineId, String tenantId) {
        public static Scope global() {
            return new Scope(ANY, ANY);
        }
    }

    /**
     * The distinct engine ids the canonical {@code search} references. Empty ⇒ the search is
     * unscoped by engine (= all enabled engines).
     */
    public static Set<String> referencedEngines(String search) {
        Set<String> engines = new LinkedHashSet<>();
        if (search == null || search.isBlank()) {
            return engines;
        }
        for (String pair : search.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = decode(pair.substring(0, eq));
            if (!ENGINES_KEY.equals(key)) {
                continue;
            }
            String value = decode(pair.substring(eq + 1));
            for (String id : value.split(LIST_SEP)) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    engines.add(trimmed);
                }
            }
        }
        return engines;
    }

    /**
     * The TIGHTEST governance scope that covers every referenced engine:
     * <ul>
     *   <li>no engines (all-engines search) ⇒ global {@code '*'/'*'};
     *   <li>exactly one engine ⇒ that engine pinned to its registry tenant ({@code '*'} if untenanted);
     *   <li>many engines sharing one tenant ⇒ {@code '*'} engine, that tenant;
     *   <li>many engines spanning tenants ⇒ global {@code '*'/'*'}.
     * </ul>
     * A broader-than-content scope is acceptable (it still {@link #contains} the content); a narrower
     * one would leak an engine outside the label and is refused at publish.
     */
    public static Scope derive(Set<String> engines, Function<String, String> tenantOf) {
        if (engines.isEmpty()) {
            return Scope.global();
        }
        if (engines.size() == 1) {
            String engine = engines.iterator().next();
            return new Scope(engine, tenantOrAny(tenantOf.apply(engine)));
        }
        String commonTenant = null;
        for (String engine : engines) {
            String tenant = tenantOrAny(tenantOf.apply(engine));
            if (ANY.equals(tenant)) {
                return Scope.global(); // an untenanted engine in the set ⇒ no common tenant.
            }
            if (commonTenant == null) {
                commonTenant = tenant;
            } else if (!commonTenant.equals(tenant)) {
                return Scope.global(); // tenants diverge.
            }
        }
        return new Scope(ANY, commonTenant);
    }

    /**
     * Does a DECLARED scope contain every engine the search references? Used at publish to bind
     * content to governance (§4.2). An all-engines search (no {@code engines}) is contained ONLY by
     * the global scope.
     */
    public static boolean contains(
            String scopeEngineId, String scopeTenantId, Set<String> engines, Function<String, String> tenantOf) {
        if (engines.isEmpty()) {
            return ANY.equals(scopeEngineId) && ANY.equals(scopeTenantId);
        }
        for (String engine : engines) {
            boolean engineOk = ANY.equals(scopeEngineId) || scopeEngineId.equals(engine);
            String tenant = tenantOrAny(tenantOf.apply(engine));
            boolean tenantOk = ANY.equals(scopeTenantId) || scopeTenantId.equals(tenant);
            if (!engineOk || !tenantOk) {
                return false;
            }
        }
        return true;
    }

    /** A wildcard scope literal has a wildcard engine OR tenant — the publish floor escalates for it. */
    public static boolean isWildcard(String scopeEngineId, String scopeTenantId) {
        return ANY.equals(scopeEngineId) || ANY.equals(scopeTenantId);
    }

    private static String tenantOrAny(String tenant) {
        return tenant == null || tenant.isBlank() ? ANY : tenant;
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
