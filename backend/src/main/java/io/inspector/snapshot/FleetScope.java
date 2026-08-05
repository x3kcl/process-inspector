package io.inspector.snapshot;

import java.util.Collection;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The canonical form of an observation SCOPE — the enabled-engine id set a Stage-0 pass fanned
 * out over (ALARM-COST-MODEL.md §16.5, issue #372). ONE canonicalizer, so every write path and
 * every reader agree on the string byte-for-byte.
 *
 * <p><b>Canonical form:</b> ids sorted lexicographically ({@link String#compareTo}), de-duplicated,
 * joined with {@code ','}. Sorting is not cosmetic: {@code perEngine} is registry-ORDERED, and a
 * pure reorder of the registry is not a composition change — without the sort, moving an engine up
 * the YAML list would read as a new fleet and needlessly void every delta at the boundary.
 *
 * <p><b>{@link #UNRECORDED}</b> ({@code ""}) means "scope was never recorded". It is comparable to
 * NOTHING — not even to another {@code ""}, because two unrecorded scopes are not KNOWN to be the
 * same scope. That is V21's fail-closed rule applied unchanged (an unknown honesty marker resolves
 * to untrusted, never to trusted), and it is why V22 does not backfill a guessed fleet.
 *
 * <p><b>The comma guard.</b> A {@code ','} inside an engine id could alias two different sets onto
 * one string ({@code {"a,b"}} vs {@code {"a","b"}}). Every write path already forecloses it —
 * {@code InspectorProperties.ENGINE_ID_PATTERN} via {@code @Pattern} on {@code EngineConfig.id}
 * (the {@code source: config} path) and {@code EngineRegistryStore.ID_PATTERN} in {@code add()}
 * (the {@code source: db} path, ids immutable on edit) — so this is belt-and-braces, not the only
 * defense. If one ever slips through we degrade to {@link #UNRECORDED} and WARN rather than record
 * a scope string we cannot honestly decode: an unrecorded scope discards deltas, an aliased one
 * would silently compare two different fleets as equal.
 */
public final class FleetScope {

    private static final Logger log = LoggerFactory.getLogger(FleetScope.class);

    /** Scope was never recorded — comparable to nothing, itself included. */
    public static final String UNRECORDED = "";

    private FleetScope() {}

    /**
     * The canonical string for one pass's enabled-engine ids. A null/empty input yields
     * {@link #UNRECORDED} — an empty enabled fleet writes no occurrence rows at all (no engines ⇒
     * no groups), so this can only ever be a call site that did not state its scope.
     */
    public static String canonical(Collection<String> engineIds) {
        if (engineIds == null || engineIds.isEmpty()) {
            return UNRECORDED;
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String id : engineIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (id.indexOf(',') >= 0) {
                log.warn(
                        "engine id {} contains a comma — refusing to record an ambiguous fleet scope;"
                                + " this row's deltas will be discarded as unrecorded (#372)",
                        id);
                return UNRECORDED;
            }
            sorted.add(id);
        }
        return sorted.isEmpty() ? UNRECORDED : String.join(",", sorted);
    }
}
