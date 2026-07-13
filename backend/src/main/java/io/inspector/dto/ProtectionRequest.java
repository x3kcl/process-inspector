package io.inspector.dto;

/** Body for both {@code protect}/{@code unprotect} (R-SAFE-05): reason required either way. */
public record ProtectionRequest(String reason) {}
