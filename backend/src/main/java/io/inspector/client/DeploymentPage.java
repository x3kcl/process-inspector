package io.inspector.client;

import java.util.List;

/** One page of the deployment list, typed (#474). */
public record DeploymentPage(List<DeploymentRecord> data, long total, int start, int size) {
    public static DeploymentPage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<DeploymentRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}
