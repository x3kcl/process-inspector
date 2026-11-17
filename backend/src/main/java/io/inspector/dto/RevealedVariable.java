package io.inspector.dto;

import java.util.UUID;

/**
 * The audited reveal's answer (#743, SPEC R-AUD-12): the UNMASKED value of ONE secret-named variable.
 * The only record besides {@link InstanceVariables.VariableDto} allowed to carry a variable value out of
 * the BFF ({@code VariableValueSurfaceArchTest} names it); built only by {@code VariableRevealService},
 * after the audit row {@code auditId} is on record. Served {@code Cache-Control: no-store}.
 */
public record RevealedVariable(
        String name, String type, Object value, String scope, String executionId, UUID auditId) {}
