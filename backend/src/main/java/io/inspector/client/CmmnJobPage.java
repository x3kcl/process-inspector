package io.inspector.client;

import java.util.List;

/** One page of a CMMN job lane, typed (#474). */
public record CmmnJobPage(List<CmmnJobRecord> data, long total, int start, int size) {
    public static CmmnJobPage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<CmmnJobRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}
