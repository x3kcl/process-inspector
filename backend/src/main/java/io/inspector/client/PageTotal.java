package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code total} of a {@code {data:[…], total, start, size}} answer whose rows are never read —
 * the {@code size=1} count trick and the capability probes (#474).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageTotal(long total) {}
