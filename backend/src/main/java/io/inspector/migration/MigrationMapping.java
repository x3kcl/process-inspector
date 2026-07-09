package io.inspector.migration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One activity-migration mapping element of a Flowable migration document's
 * {@code activityMappings} array (P0 spike 2026-07-09: the top-level field is
 * {@code activityMappings}, NOT {@code activityMigrationMappings} — the latter is the
 * engine builder's internal method name; confirmed against
 * {@code ProcessInstanceMigrationDocumentConverter} bytecode on 6.8/7.1).
 *
 * <p>The engine's converter discriminates three forms by which fields are present, and the
 * DTO models all three even though slice-1's UI drives only one-to-one — a one-to-one-only
 * DTO would make gateway / multi-instance renames unexpressible:
 *
 * <ul>
 *   <li><b>one-to-one</b> — {@code fromActivityId} + {@code toActivityId}
 *   <li><b>many-to-one</b> — {@code fromActivityIds[]} + {@code toActivityId}
 *   <li><b>one-to-many</b> — {@code fromActivityId} + {@code toActivityIds[]}
 * </ul>
 *
 * Many-to-many is not a form the engine converter accepts and is rejected here. Per-mapping
 * {@code newAssignee}/{@code localVariables} are converter keys deliberately OUT of slice-1.
 */
public record MigrationMapping(
        String fromActivityId, List<String> fromActivityIds, String toActivityId, List<String> toActivityIds) {

    public enum Form {
        ONE_TO_ONE,
        MANY_TO_ONE,
        ONE_TO_MANY
    }

    /** Convenience factory for the only form slice-1's UI produces. */
    public static MigrationMapping oneToOne(String from, String to) {
        return new MigrationMapping(from, null, to, null);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean present(List<String> l) {
        return l != null && !l.isEmpty() && l.stream().allMatch(MigrationMapping::present);
    }

    /**
     * The mapping's form, or a rejection if the from/to fields do not describe exactly one
     * supported shape. Called by {@link #toWire()} and the service's request validation so a
     * malformed mapping never reaches the engine.
     */
    public Form form() {
        boolean fromSingle = present(fromActivityId);
        boolean fromMulti = present(fromActivityIds);
        boolean toSingle = present(toActivityId);
        boolean toMulti = present(toActivityIds);

        if (fromSingle == fromMulti) {
            throw new IllegalArgumentException("mapping must set exactly one of fromActivityId or fromActivityIds (got "
                    + (fromSingle && fromMulti ? "both" : "neither") + ")");
        }
        if (toSingle == toMulti) {
            throw new IllegalArgumentException("mapping must set exactly one of toActivityId or toActivityIds (got "
                    + (toSingle && toMulti ? "both" : "neither") + ")");
        }
        if (fromMulti && toMulti) {
            throw new IllegalArgumentException(
                    "many-to-many mapping is not supported by the engine — split it into separate mappings");
        }
        if (fromMulti) {
            return Form.MANY_TO_ONE;
        }
        if (toMulti) {
            return Form.ONE_TO_MANY;
        }
        return Form.ONE_TO_ONE;
    }

    /** Serialize to the exact JSON object shape the Flowable migration document expects. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        switch (form()) {
            case ONE_TO_ONE -> {
                wire.put("fromActivityId", fromActivityId);
                wire.put("toActivityId", toActivityId);
            }
            case MANY_TO_ONE -> {
                wire.put("fromActivityIds", List.copyOf(fromActivityIds));
                wire.put("toActivityId", toActivityId);
            }
            case ONE_TO_MANY -> {
                wire.put("fromActivityId", fromActivityId);
                wire.put("toActivityIds", List.copyOf(toActivityIds));
            }
        }
        return wire;
    }

    /** The source activity ids this mapping covers (1 for one-to-*, N for many-to-one). */
    public List<String> coveredFromIds() {
        return switch (form()) {
            case ONE_TO_ONE, ONE_TO_MANY -> List.of(fromActivityId);
            case MANY_TO_ONE -> List.copyOf(fromActivityIds);
        };
    }
}
