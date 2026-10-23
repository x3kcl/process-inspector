package io.inspector.client;

/**
 * CMMN migration routes (issue #500, INSTANCE-MIGRATION.md §7). {@link #SINGLE} exists on 6.8 and
 * 7.x. Every other route is 7.x only and is refused before HTTP on 6.x.
 */
public enum CaseMigrationRoute {
    SINGLE("/cmmn-runtime/case-instances/{id}/migrate", false),
    HISTORIC("/cmmn-history/historic-case-instances/{id}/migrate", true),
    BY_DEFINITION("/cmmn-repository/case-definitions/{id}/migrate", true),
    BY_DEFINITION_BATCH("/cmmn-repository/case-definitions/{id}/batch-migrate", true),
    BY_DEFINITION_HISTORIC("/cmmn-repository/case-definitions/{id}/migrate-historic-instances", true),
    BY_DEFINITION_BATCH_HISTORIC("/cmmn-repository/case-definitions/{id}/batch-migrate-historic-instances", true);

    private final String path;
    private final boolean requires7;

    CaseMigrationRoute(String path, boolean requires7) {
        this.path = path;
        this.requires7 = requires7;
    }

    /** Path under {@code /cmmn-api}, with a single {@code {id}} template. */
    public String path() {
        return path;
    }

    /** True for by-definition, batch, and historic routes. */
    public boolean requires7() {
        return requires7;
    }
}
