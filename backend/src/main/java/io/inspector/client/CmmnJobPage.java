package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a CMMN job lane, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CmmnJobPage(List<CmmnJobRecord> data, long total, int start, int size) {
    public static CmmnJobPage empty() {
        return new CmmnJobPage(List.of(), 0, 0, 0);
    }

    public List<CmmnJobRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}
