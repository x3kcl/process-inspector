package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code /cmmn-runtime/case-instances/{id}/identitylinks} (#474). Exactly the keys the
 * engine sends: the case-detail read hands these rows on to the SPA as they are.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseIdentityLinkRecord(String url, String user, String group, String type) {}
