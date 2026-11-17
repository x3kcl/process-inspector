package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One CMMN case definition as {@code /cmmn-repository/case-definitions} (list and by id) serialises
 * it — the typed wire shape of the case-definition reads (#474), the CMMN sibling of
 * {@link ProcessDefinitionRecord}. {@code id} is a bare uuid (no {@code key:version}). 6.8 and 7.1
 * serialise the CMMN resource only as a URL ({@code resource}) and no {@code suspended} flag;
 * {@code resourceName} and {@code suspended} are read when an engine sends them. A field the
 * engine does not serialise is null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseDefinitionRecord(
        String id,
        String key,
        String name,
        Integer version,
        Boolean suspended,
        String deploymentId,
        String category,
        String tenantId,
        String resource,
        String resourceName,
        Boolean graphicalNotationDefined) {}
