package io.inspector.views;

import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.views.SharedViewScope.Scope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Team-published shared views (SPEC §8, SHARED-VIEWS.md, R-SEM-24). Distinct from the per-user
 * {@link ViewStoreService}: these rows are team canon, not owner-keyed prefs. This slice (S2) is the
 * READ path only — publish/unpublish/moderate + the audited governance rails land in S3.
 *
 * <p>Read-visibility is an {@code overlaps()} INTERSECTION over the caller's resolved grants (§4.3),
 * NOT owner-keying and NOT {@code covers()} containment. It is DECLUTTER, not a security boundary —
 * search results are grant-blind, so this only tidies which canon the picker offers.
 */
@Service
public class SharedViewService {

    private static final Logger log = LoggerFactory.getLogger(SharedViewService.class);

    /** R-OPS-08 length caps on authored text shown to other users. */
    static final int MAX_NAME = 200;

    static final int MAX_SEARCH = 4000;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_RUNBOOK_URL = 2000;
    /** Reason floor for moderating ANOTHER author's canon (SHARED-VIEWS.md §4.4). */
    static final int MIN_MODERATION_REASON = 10;

    private final SharedViewRepository repository;
    private final RbacAuthorizer rbac;
    private final EngineRegistry registry;
    private final AuditService audit;
    private final java.time.Clock clock;

    public SharedViewService(
            SharedViewRepository repository,
            RbacAuthorizer rbac,
            EngineRegistry registry,
            AuditService audit,
            java.time.Clock clock) {
        this.repository = repository;
        this.rbac = rbac;
        this.registry = registry;
        this.audit = audit;
        this.clock = clock;
    }

    /** The team canon this caller may SEE — every shared view whose scope overlaps a grant of theirs. */
    public List<SharedView> listVisible(Authentication auth) {
        return filterVisible(rbac.grantsFor(auth), repository.findAllByOrderByCreatedAtDesc());
    }

    /** A visible team view paired with its picker dangling reason ({@code null} when it still resolves). */
    public record VisibleSharedView(SharedView view, String danglingReason) {}

    /**
     * The team canon for the picker (S5): the caller's visible views, each tagged with its dangling
     * reason. The live enabled-engine set is computed ONCE for the whole list (not per view) — the
     * registry read is in-memory, but a shared set keeps the mapping O(views), not O(views·engines).
     */
    public List<VisibleSharedView> listVisibleForDisplay(Authentication auth) {
        Set<String> enabled = new java.util.HashSet<>();
        registry.all().forEach(e -> enabled.add(e.id()));
        return filterVisible(rbac.grantsFor(auth), repository.findAllByOrderByCreatedAtDesc()).stream()
                .map(v -> new VisibleSharedView(v, danglingReason(v.getScopeEngineId(), enabled)))
                .toList();
    }

    /**
     * Replay-time resolvability honesty for the PICKER (SHARED-VIEWS.md §4.5, R-SEM-24): a shared view
     * scoped to a concrete engine that is no longer a live ENABLED engine (soft-tombstoned or disabled
     * by Registry CRUD) is DANGLING — the frontend greys it with this reason so a responder never
     * clicks a dead entry point that would otherwise replay to a clean-looking "no failures". Returns
     * {@code null} when the canon still resolves. A wildcard-engine scope can't dangle on one engine.
     */
    public String danglingReason(SharedView view) {
        Set<String> enabled = new java.util.HashSet<>();
        registry.all().forEach(e -> enabled.add(e.id()));
        return danglingReason(view.getScopeEngineId(), enabled);
    }

    /** Pure form (rung-1): dangling iff the concrete scope engine is not among the live enabled ids. */
    public static String danglingReason(String scopeEngineId, Set<String> enabledEngineIds) {
        if (SharedView.ANY.equals(scopeEngineId)) {
            return null; // a wildcard-engine scope resolves as long as any engine is live
        }
        return enabledEngineIds.contains(scopeEngineId)
                ? null
                : "the engine \"" + scopeEngineId
                        + "\" this team view is scoped to is not currently available (removed or disabled)";
    }

    /**
     * Pure visibility filter (rung-1 testable with crafted {@link ScopeGrant} sets — the scoped RBAC
     * cases the dev basic-auth ladder can't express, since it only ever mints global grants). A view
     * is visible iff SOME grant overlaps its scope at the VIEWER floor.
     */
    public static List<SharedView> filterVisible(Set<ScopeGrant> grants, List<SharedView> all) {
        return all.stream().filter(view -> isVisible(grants, view)).toList();
    }

    private static boolean isVisible(Set<ScopeGrant> grants, SharedView view) {
        return grants.stream().anyMatch(g -> g.overlaps(Role.VIEWER, view.getScopeEngineId(), view.getScopeTenantId()));
    }

    /**
     * The engine→tenant seam for scope derivation/validation (S3): the registry-pinned tenant, or
     * {@code null} when the engine is unknown/untenanted. Resolves DISABLED engines too so a scope
     * still derives when an engine is temporarily off (dangling-canon honesty is S4, not here).
     */
    public String tenantOf(String engineId) {
        return registry.resolve(engineId).map(e -> e.tenantId()).orElse(null);
    }

    /* ---------------- governance: publish / edit / unpublish (S3) ---------------- */

    /**
     * Publish a private view's snapshot into team canon (SHARED-VIEWS.md §4.1/§4.2/§4.4). Create-only
     * (an overwrite of an existing {@code (name, scope)} is a moderation act, never a blind upsert).
     * The scope is DERIVED from the search's engines when {@code requestedScope*} is null; a
     * client-supplied scope is honored ONLY if it still {@link SharedViewScope#contains} the search's
     * content (governance == content, §4.2). The publish gate is {@code covers()} containment at the
     * OPERATOR floor — ADMIN for a wildcard scope (§4.3, R-SAFE-14 breadth). Audited fail-closed via
     * {@code recordConfigEvent} with a write→audit→compensate shape (LegalHoldService precedent —
     * {@code recordConfigEvent} serializes the tamper chain and cannot join an outer transaction).
     */
    public SharedView publish(
            Authentication auth,
            String rawName,
            String rawSearch,
            String rawDescription,
            String rawRunbookUrl,
            String requestedScopeEngineId,
            String requestedScopeTenantId) {
        String actor = auth.getName();
        String name = requireLine(rawName, MAX_NAME, "name");
        String search = requireSearch(rawSearch);
        String description = optionalText(rawDescription, MAX_DESCRIPTION, "description");
        String runbookUrl = optionalUrl(rawRunbookUrl);

        Set<String> engines = SharedViewScope.referencedEngines(search);
        Scope scope = requestedScopeEngineId == null
                ? SharedViewScope.derive(engines, this::tenantOf)
                : new Scope(
                        requestedScopeEngineId,
                        requestedScopeTenantId == null ? SharedView.ANY : requestedScopeTenantId);

        if (!SharedViewScope.contains(scope.engineId(), scope.tenantId(), engines, this::tenantOf)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "the declared scope does not cover every engine this view's search queries"
                            + " — it would publish canon that reaches outside its label");
        }
        if (!canPublish(rbac.grantsFor(auth), scope.engineId(), scope.tenantId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "publishing this scope needs an OPERATOR (or ADMIN for a wildcard scope) grant on it");
        }
        if (repository
                .findByNameAndScopeEngineIdAndScopeTenantId(name, scope.engineId(), scope.tenantId())
                .isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "team canon named \"" + name + "\" already exists in this scope");
        }

        SharedView saved;
        try {
            saved = repository.saveAndFlush(new SharedView(
                    actor, name, search, scope.engineId(), scope.tenantId(), description, runbookUrl, clockNow()));
        } catch (DataIntegrityViolationException race) {
            // A concurrent publish won the unique (name, scope) — a clean 409, never a bare 500.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "team canon named \"" + name + "\" already exists in this scope");
        }
        auditOrCompensate("view-publish", actor, lifecyclePayload(saved, "PRIVATE", "SHARED", null), () -> {
            repository.delete(saved);
            repository.flush();
        });
        return saved;
    }

    /**
     * Edit published canon in place (§4.4). Name + scope are the immutable identity; only the body
     * (search/description/runbook) changes, and the NEW search must still fit the EXISTING scope (an
     * edit can't broaden canon past its declared label). Author edits their own; a scope-ADMIN
     * moderating ANOTHER's must supply a reason ≥10 (and it fires the security-alert substrate).
     */
    public SharedView edit(
            Authentication auth,
            Long id,
            String rawSearch,
            String rawDescription,
            String rawRunbookUrl,
            String reason) {
        SharedView view = load(id);
        requireModerationAuthority(auth, view, reason, "view-update");

        String search = requireSearch(rawSearch);
        String description = optionalText(rawDescription, MAX_DESCRIPTION, "description");
        String runbookUrl = optionalUrl(rawRunbookUrl);
        Set<String> engines = SharedViewScope.referencedEngines(search);
        if (!SharedViewScope.contains(view.getScopeEngineId(), view.getScopeTenantId(), engines, this::tenantOf)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "the edited search reaches engines outside this canon's scope");
        }

        String oldSearch = view.getSearch();
        String oldDescription = view.getDescription();
        String oldRunbook = view.getRunbookUrl();
        java.time.Instant oldUpdatedAt = view.getUpdatedAt();
        view.edit(search, description, runbookUrl, clockNow());
        SharedView saved = repository.saveAndFlush(view);
        auditOrCompensate("view-update", auth.getName(), lifecyclePayload(saved, "SHARED", "SHARED", reason), () -> {
            saved.edit(oldSearch, oldDescription, oldRunbook, oldUpdatedAt);
            repository.saveAndFlush(saved);
        });
        return saved;
    }

    /**
     * Unpublish = remove from team canon (§4.4, the default moderation verb). Because publish was a
     * snapshot-COPY (§4.1), the author's private bookmark was never consumed and survives untouched —
     * so this is the reversible "demote to private" with no re-materialization needed. Author removes
     * their own; a scope-ADMIN moderating another's supplies a reason ≥10 + fires the alert substrate.
     */
    public void unpublish(Authentication auth, Long id, String reason) {
        SharedView view = load(id);
        requireModerationAuthority(auth, view, reason, "view-unpublish");

        Map<String, Object> payload = lifecyclePayload(view, "SHARED", "PRIVATE", reason);
        SharedView snapshot = detach(view);
        repository.delete(view);
        repository.flush();
        auditOrCompensate("view-unpublish", auth.getName(), payload, () -> repository.saveAndFlush(snapshot));
    }

    /* ---------------- pure decision helpers (rung-1 testable) ---------------- */

    /**
     * The publish gate: {@code covers()} containment at the OPERATOR floor, escalated to ADMIN for any
     * wildcard scope (§4.3). Pure over a crafted grant set so the scoped matrix is rung-1 testable
     * (the dev basic-auth ladder only mints global grants).
     */
    public static boolean canPublish(Set<ScopeGrant> grants, String scopeEngineId, String scopeTenantId) {
        Role floor = SharedViewScope.isWildcard(scopeEngineId, scopeTenantId) ? Role.ADMIN : Role.OPERATOR;
        return grants.stream().anyMatch(g -> g.covers(floor, scopeEngineId, scopeTenantId));
    }

    /** Moderation authority: the author always, else an ADMIN whose grant covers the canon's scope. */
    public static boolean canModerate(
            Set<ScopeGrant> grants, String author, String caller, String scopeEngineId, String scopeTenantId) {
        if (author.equals(caller)) {
            return true;
        }
        return grants.stream().anyMatch(g -> g.covers(Role.ADMIN, scopeEngineId, scopeTenantId));
    }

    /* ---------------- internals ---------------- */

    private void requireModerationAuthority(Authentication auth, SharedView view, String reason, String action) {
        String caller = auth.getName();
        boolean isAuthor = view.getAuthor().equals(caller);
        if (!canModerate(
                rbac.grantsFor(auth), view.getAuthor(), caller, view.getScopeEngineId(), view.getScopeTenantId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "only the author or an ADMIN on this canon's scope may " + action);
        }
        if (!isAuthor) {
            String clean = reason == null ? "" : reason.strip();
            if (clean.length() < MIN_MODERATION_REASON) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "moderating another author's team canon requires a reason of at least " + MIN_MODERATION_REASON
                                + " characters");
            }
            // Interim alert substrate (a ≤25-user org can't segregate an approver class — R-SAFE-14).
            // A stable, greppable marker for log-based alerting until the IdP-Security alert channel
            // + R-OPS-02 metric bind here, mirroring AuditService's AUDIT_CONFIG_EVENT_FAILURE pattern.
            log.warn(
                    "SHARED_VIEW_MODERATION action={} moderator={} author={} name=\"{}\" scope={}/{} —"
                            + " another author's team canon was moderated",
                    action,
                    caller,
                    view.getAuthor(),
                    view.getName(),
                    view.getScopeEngineId(),
                    view.getScopeTenantId());
        }
    }

    /** Write the config event; on audit failure run {@code compensate} and refuse fail-closed (503). */
    private void auditOrCompensate(String action, String actor, Map<String, Object> payload, Runnable compensate) {
        try {
            audit.recordConfigEvent(action, actor, true, payload);
        } catch (AuditUnavailableException e) {
            compensate.run();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Refused fail-closed: the change was NOT applied because the audit store is unavailable",
                    e);
        }
    }

    private SharedView load(Long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such team view"));
    }

    /** A fresh detached copy (new row on re-insert) for delete compensation — id identity is internal. */
    private static SharedView detach(SharedView v) {
        return new SharedView(
                v.getAuthor(),
                v.getName(),
                v.getSearch(),
                v.getScopeEngineId(),
                v.getScopeTenantId(),
                v.getDescription(),
                v.getRunbookUrl(),
                v.getCreatedAt());
    }

    private Map<String, Object> lifecyclePayload(SharedView v, String before, String after, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", v.getName());
        p.put("scopeEngineId", v.getScopeEngineId());
        p.put("scopeTenantId", v.getScopeTenantId());
        p.put("author", v.getAuthor());
        // The raw search may embed business keys — audit a HASH, never the query text (§4.4, A5).
        p.put("searchSha256", sha256(v.getSearch()));
        p.put("visibilityBefore", before);
        p.put("visibilityAfter", after);
        if (reason != null && !reason.isBlank()) {
            p.put("reason", reason.strip());
        }
        return p;
    }

    private java.time.Instant clockNow() {
        return clock.instant();
    }

    /* ---------------- R-OPS-08 ingest sanitation ---------------- */

    private static String requireLine(String raw, int cap, String field) {
        String clean = stripLine(raw);
        if (clean.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must not be blank");
        }
        if (clean.length() > cap) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, field + " must be at most " + cap + " characters");
        }
        return clean;
    }

    private static String requireSearch(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "search must not be blank");
        }
        if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "search must be a single-line URL query string");
        }
        String clean = raw.strip();
        if (clean.length() > MAX_SEARCH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "search must be at most " + MAX_SEARCH + " characters");
        }
        return clean;
    }

    private static String optionalText(String raw, int cap, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // Text-is-data: collapse CR/LF (single-logical-line storage) and cap (R-OPS-08).
        String clean = raw.replace('\r', ' ').replace('\n', ' ').strip();
        if (clean.length() > cap) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, field + " must be at most " + cap + " characters");
        }
        return clean.isEmpty() ? null : clean;
    }

    private static String optionalUrl(String raw) {
        String clean = optionalText(raw, MAX_RUNBOOK_URL, "runbookUrl");
        if (clean == null) {
            return null;
        }
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // Only http(s) — a javascript:/data: runbook URL is an XSS vector when rendered as a link.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runbookUrl must be an http(s) URL");
        }
        return clean;
    }

    private static String stripLine(String raw) {
        return raw == null ? "" : raw.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JVM
        }
    }
}
