package io.inspector.views;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One user's recent search (SPEC §8): a canonical URL search string + its frozen label. Maps
 * onto {@code recent_search} (V6). Newest-first, deduped by search, capped per user in the BFF.
 */
@Entity
@Table(name = "recent_search")
public class RecentSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "owner", nullable = false, updatable = false)
    private String owner;

    @Column(name = "search", nullable = false, updatable = false)
    private String search;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected RecentSearch() {
        // JPA
    }

    public RecentSearch(String owner, String search, String label, Instant at) {
        this.owner = owner;
        this.search = search;
        this.label = label;
        this.at = at;
    }

    /** Re-recording the same search bumps its recency (and refreshes the label). */
    public void touch(String label, Instant at) {
        this.label = label;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getSearch() {
        return search;
    }

    public String getLabel() {
        return label;
    }

    public Instant getAt() {
        return at;
    }
}
