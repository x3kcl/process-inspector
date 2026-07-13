package io.inspector.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body for both {@code protect}/{@code unprotect} (R-SAFE-05): reason required either way. */
public record ProtectionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 10)
        String reason) {}
