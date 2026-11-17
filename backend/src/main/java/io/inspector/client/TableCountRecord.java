package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One row of the bare-array {@code /management/tables} answer (#627), typed (#474). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TableCountRecord(String name, Long count) {}
