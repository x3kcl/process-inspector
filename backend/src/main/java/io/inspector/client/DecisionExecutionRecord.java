package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code /dmn-api/dmn-history/historic-decision-executions} (#474). 6.8 and 7.1 name the
 * decision {@code decisionKey}; {@code decisionDefinitionKey} is kept for an engine that serialises
 * the older name (absent is null). Times stay the engine's verbatim text.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionExecutionRecord(
        String id,
        String decisionKey,
        String decisionDefinitionKey,
        String activityId,
        Boolean failed,
        String startTime,
        String endTime) {}
