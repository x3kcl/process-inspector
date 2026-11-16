package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of the deployment list, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeploymentPage(List<DeploymentRecord> data, long total, int start, int size) {
    public static DeploymentPage empty() {
        return new DeploymentPage(List.of(), 0, 0, 0);
    }

    public List<DeploymentRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}
