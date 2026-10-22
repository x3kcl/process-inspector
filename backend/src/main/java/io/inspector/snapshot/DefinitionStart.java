package io.inspector.snapshot;

/** Starts of one definition key during one sample bucket. */
public record DefinitionStart(String engineId, String key, long count, boolean catalogCapped) {}
