package io.inspector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Test fixture: build typed identity-link rows from the wire-keyed maps the tests already read as JSON. */
public final class IdentityLinkRecords {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private IdentityLinkRecords() {}

    public static IdentityLinkRecord link(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, IdentityLinkRecord.class);
    }

    public static List<IdentityLinkRecord> links(List<? extends Map<String, ?>> wire) {
        return wire.stream().map(IdentityLinkRecords::link).toList();
    }
}
