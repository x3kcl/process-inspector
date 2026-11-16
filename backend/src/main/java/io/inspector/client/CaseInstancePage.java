package io.inspector.client;

import java.util.List;

/** One page of a case-instance list, typed (#474). */
public record CaseInstancePage(List<CaseInstanceRecord> data, long total, int start, int size) {
    public static CaseInstancePage empty() {
        throw new UnsupportedOperationException("stub (#474)");
    }

    public List<CaseInstanceRecord> dataOrEmpty() {
        throw new UnsupportedOperationException("stub (#474)");
    }
}
