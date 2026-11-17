package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A task field whose key is SENT (#720), also when the value is null — the engine reads a null as
 * "clear it" and an absent key as "leave it". A null {@code TaskField} in a request record is the
 * absent key. On the wire it is the bare value.
 */
public record TaskField(@JsonValue String value) {}
