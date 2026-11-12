package io.inspector.bulk;

import io.inspector.action.GuardRefusedException;
import io.inspector.migration.HierarchyPlanService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;

/**
 * Dispatch order of a hierarchy migration job (#497): children first, then parent.
 *
 * <ul>
 *   <li>{@link #levels}: an engine's items grouped by tree depth, deepest first. A level is
 *       dispatched (paced, permit-bounded, like any bulk group) and fully settled before the next
 *       one starts.
 *   <li>{@link #blockedBy}: a parent is dispatched only when every item that names it as parent
 *       reached its target, or has nothing left to migrate ({@link #released}). Any other child
 *       outcome blocks it, and the parent settles {@code not_run} naming that child. Never the
 *       reverse: a parent's outcome does not touch its children, and siblings are independent.
 *   <li>{@link #carryOver}: "Continue as new job" keeps the order, and refuses a parent whose
 *       child still has something to migrate and is not part of the continuation.
 * </ul>
 *
 * A job with no placed item (every other bulk verb) is one level with nothing blocked.
 */
final class HierarchyOrder {

    private static final int CAUSE_MAX = 300;

    private HierarchyOrder() {}

    /**
     * Where one item sits in the plan's tree. {@code parentInstanceId} is the nearest ancestor that
     * is itself an item of the job, null for a top item. {@code notSentReason} settles the item
     * {@code not_run} at submit: the analysis found a blocker, so it is listed and never sent.
     */
    record Placement(int level, String parentInstanceId, String notSentReason) {}

    static String key(String engineId, String instanceId) {
        return engineId + ":" + instanceId;
    }

    /** Deepest level first. Items without a level (every non-hierarchy job) form one group. */
    static List<List<BulkJobItem>> levels(List<BulkJobItem> group) {
        if (group.stream().noneMatch(item -> item.getLevel() != null)) {
            return List.of(group);
        }
        TreeMap<Integer, List<BulkJobItem>> byLevel = new TreeMap<>();
        for (BulkJobItem item : group) {
            byLevel.computeIfAbsent(item.getLevel() != null ? item.getLevel() : 0, k -> new ArrayList<>())
                    .add(item);
        }
        return List.copyOf(byLevel.descendingMap().values());
    }

    /**
     * The reason {@code item} must not be dispatched, or null when it may go. {@code nothingLeft}
     * is the read-only "is this instance on its target definition now, or no longer running"
     * check ({@code CohortBulkMigration#nothingLeftToMigrate}); it is asked only for a child that
     * was never sent ({@code not_run}) or settled {@code skipped}.
     */
    static String blockedBy(BulkJobItem item, Collection<BulkJobItem> all, Predicate<BulkJobItem> nothingLeft) {
        if (item.getLevel() == null) {
            return null;
        }
        for (BulkJobItem child : all) {
            if (child == item
                    || !item.getInstanceId().equals(child.getParentInstanceId())
                    || !item.getEngineId().equals(child.getEngineId())) {
                continue;
            }
            if (!released(child, nothingLeft)) {
                return "blocked by child " + key(child.getEngineId(), child.getInstanceId()) + " ("
                        + child.getState() + cause(child.getDetail())
                        + ") — not attempted. A parent is migrated only after every planned child reached its"
                        + " target.";
            }
        }
        return null;
    }

    /**
     * A child releases its parent when it migrated, or when nothing is left of it to migrate: it
     * is no longer running, or a read shows it on its target. That read is asked for a child that
     * was never sent ({@code not_run}: the analysis blocked it, or its own child blocked it) and
     * for a {@code skipped} child — whether the child ended or was moved before its analysis ran
     * or after. A {@code failed}, {@code unknown} or protected child always blocks.
     */
    static boolean released(BulkJobItem child, Predicate<BulkJobItem> nothingLeft) {
        BulkJobItem.State state = child.getState();
        if (state == BulkJobItem.State.ok) {
            return true;
        }
        if (state == BulkJobItem.State.not_run) {
            return nothingLeft.test(child);
        }
        if (state != BulkJobItem.State.skipped) {
            return false;
        }
        String detail = child.getDetail();
        return (detail != null && detail.startsWith("instance-not-running")) || nothingLeft.test(child);
    }

    /**
     * Placements for a continuation: each continued item keeps the level and parent it had in
     * the prior job, and an item the analysis blocked stays "listed, never sent". A continued
     * parent whose prior child is not continued with it must have nothing left to migrate (on its
     * target, or no longer running), else the continuation refuses — it would migrate the parent
     * over a child that did not move.
     */
    static Map<String, Placement> carryOver(
            List<BulkJobItem> prior, List<BulkDtos.BulkTarget> targets, Predicate<BulkJobItem> nothingLeft) {
        Map<String, BulkJobItem> priorByKey = new HashMap<>();
        for (BulkJobItem item : prior) {
            priorByKey.put(key(item.getEngineId(), item.getInstanceId()), item);
        }
        Set<String> continued = new HashSet<>();
        for (BulkDtos.BulkTarget target : targets) {
            continued.add(key(target.engineId(), target.instanceId()));
        }
        Map<String, Placement> placements = new HashMap<>();
        for (BulkDtos.BulkTarget target : targets) {
            String key = key(target.engineId(), target.instanceId());
            BulkJobItem was = priorByKey.get(key);
            if (was == null || was.getLevel() == null) {
                continue;
            }
            placements.put(key, new Placement(was.getLevel(), was.getParentInstanceId(), notSentReason(was)));
            for (BulkJobItem child : prior) {
                if (!was.getInstanceId().equals(child.getParentInstanceId())
                        || !was.getEngineId().equals(child.getEngineId())
                        || continued.contains(key(child.getEngineId(), child.getInstanceId()))) {
                    continue;
                }
                if (!released(child, nothingLeft) && !nothingLeft.test(child)) {
                    throw new GuardRefusedException(
                            HttpStatus.CONFLICT,
                            "hierarchy-child-unresolved",
                            "Instance " + key + " cannot be continued alone: its child "
                                    + key(child.getEngineId(), child.getInstanceId()) + " settled " + child.getState()
                                    + " and still has to be migrated. Continue the child with it, or verify the child"
                                    + " first. Nothing happened.");
                }
            }
        }
        return placements;
    }

    /** The prior job listed this item unsent because its analysis blocked it: it stays unsent. */
    private static String notSentReason(BulkJobItem was) {
        String detail = was.getDetail();
        return was.getState() == BulkJobItem.State.not_run
                        && detail != null
                        && detail.startsWith(HierarchyPlanService.NOT_SENT)
                ? detail
                : null;
    }

    private static String cause(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        return ": " + (detail.length() > CAUSE_MAX ? detail.substring(0, CAUSE_MAX) + "…" : detail);
    }
}
