package io.inspector.views;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One team-published shared view (SPEC §8, SHARED-VIEWS.md, R-SEM-24): a curated canonical URL
 * search string the team inherits, in a SEPARATE governed store from the per-user {@link SavedView}
 * (§4.1) — so {@code ViewStoreService}'s "owner-keyed prefs, no rails" invariant stays intact and
 * the preference→governance line is the table boundary. Maps onto {@code shared_view} (V12) —
 * schema is the truth, {@code ddl-auto=validate}.
 *
 * <p>{@code author} is attribution only (server-derived at publish), never a read gate. {@code name}
 * and the derived {@code scopeEngineId}/{@code scopeTenantId} form the canon identity
 * ({@code UNIQUE(name, scope_engine_id, scope_tenant_id)}) and are immutable — publish is
 * create-only (a snapshot-copy), an overwrite of an existing {@code (name, scope)} is a moderation
 * act, not a blind upsert. {@code search}/{@code description}/{@code runbookUrl} are the editable
 * canon body. {@code '*'} is the global wildcard scope (never null).
 */
@Entity
@Table(name = "shared_view")
public class SharedView {

    /** The global wildcard scope literal — mirrors {@code ScopeGrant.ANY}. */
    public static final String ANY = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "author", nullable = false, updatable = false)
    private String author;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "search", nullable = false)
    private String search;

    @Column(name = "scope_engine_id", nullable = false, updatable = false)
    private String scopeEngineId;

    @Column(name = "scope_tenant_id", nullable = false, updatable = false)
    private String scopeTenantId;

    @Column(name = "description")
    private String description;

    @Column(name = "runbook_url")
    private String runbookUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SharedView() {
        // JPA
    }

    public SharedView(
            String author,
            String name,
            String search,
            String scopeEngineId,
            String scopeTenantId,
            String description,
            String runbookUrl,
            Instant now) {
        this.author = author;
        this.name = name;
        this.search = search;
        this.scopeEngineId = scopeEngineId;
        this.scopeTenantId = scopeTenantId;
        this.description = description;
        this.runbookUrl = runbookUrl;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Editing published canon replaces the body in place (identity name+scope is immutable). */
    public void edit(String search, String description, String runbookUrl, Instant now) {
        this.search = search;
        this.description = description;
        this.runbookUrl = runbookUrl;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getAuthor() {
        return author;
    }

    public String getName() {
        return name;
    }

    public String getSearch() {
        return search;
    }

    public String getScopeEngineId() {
        return scopeEngineId;
    }

    public String getScopeTenantId() {
        return scopeTenantId;
    }

    public String getDescription() {
        return description;
    }

    public String getRunbookUrl() {
        return runbookUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
