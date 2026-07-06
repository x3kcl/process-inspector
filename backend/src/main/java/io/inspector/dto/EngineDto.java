package io.inspector.dto;

/** Registry entry as exposed to the UI — never carries credentials or secret refs. */
public record EngineDto(
        String id,
        String name,
        String environment,
        String color,
        boolean reachable,
        String engineVersion,
        String healthError
) {}
