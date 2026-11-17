package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One {@code {data:[…], total, start, size}} page of a case-instance list, typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseInstancePage(List<CaseInstanceRecord> data, long total, int start, int size) {
    public static CaseInstancePage empty() {
        return new CaseInstancePage(List.of(), 0, 0, 0);
    }

    public List<CaseInstanceRecord> dataOrEmpty() {
        return data != null ? data : List.of();
    }
}
