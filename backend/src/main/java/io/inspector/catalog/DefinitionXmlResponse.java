package io.inspector.catalog;

/** Deployed BPMN (or CMMN) XML. {@code xml} is null when the engine has no resource for the id. */
public record DefinitionXmlResponse(String xml) {}
