package io.inspector.views;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One user's named saved view (SPEC §8): a canonical URL search string under a unique name.
 * Maps onto {@code saved_view} (V6) — schema is the truth, {@code ddl-auto=validate}. Owned by
 * {@code owner} (the authenticated user); the BFF scopes every access to the caller.
 */
@Entity
@Table(name = "saved_view")
public class SavedView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "owner", nullable = false, updatable = false)
    private String owner;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "search", nullable = false)
    private String search;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SavedView() {
        // JPA
    }

    public SavedView(String owner, String name, String search, Instant createdAt) {
        this.owner = owner;
        this.name = name;
        this.search = search;
        this.createdAt = createdAt;
    }

    /** Re-saving under an existing name replaces the search + bumps the timestamp in place. */
    public void replace(String search, Instant createdAt) {
        this.search = search;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getSearch() {
        return search;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
