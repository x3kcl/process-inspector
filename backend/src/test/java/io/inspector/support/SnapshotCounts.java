package io.inspector.support;

import io.inspector.snapshot.SnapshotCount;
import io.inspector.snapshot.SnapshotLane;
import java.lang.reflect.Field;
import java.time.Instant;

/**
 * Test-only fixture builder for {@link SnapshotCount}. The entity has a protected no-arg ctor and
 * DB-assigned read-only fields (no setters — writes go through the native upsert), so a test row
 * is assembled via reflection here rather than adding a production constructor just for tests.
 */
public final class SnapshotCounts {

    private SnapshotCounts() {}

    public static SnapshotCount row(String engineId, SnapshotLane lane, long count, String sampledAt) {
        try {
            var ctor = SnapshotCount.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            SnapshotCount r = ctor.newInstance();
            set(r, "engineId", engineId);
            set(r, "lane", lane);
            set(r, "count", count);
            set(r, "sampledAt", Instant.parse(sampledAt));
            return r;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(SnapshotCount target, String field, Object value) throws ReflectiveOperationException {
        Field f = SnapshotCount.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
