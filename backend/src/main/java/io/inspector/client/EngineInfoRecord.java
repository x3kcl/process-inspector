package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The {@code /management/engine} document — the health + version probe (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineInfoRecord(String name, String version) {}
