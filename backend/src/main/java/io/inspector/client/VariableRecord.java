package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One variable ENVELOPE as every variable endpoint serialises it — process and execution scope,
 * the {@code variable} of a historic row, a CMMN case variable (#474). The envelope is typed; the
 * {@code value} is the one place the engine is genuinely dynamic and stays whatever JSON it sent
 * (a number at its JSON width, a date as its text, a json variable as a map/list).
 *
 * <p>{@code type} is the engine's type string verbatim and is ABSENT on a null-valued variable;
 * {@code valueUrl} exists only on a binary/serializable variable (which has no inline value).
 * Those two are left out when null, so a row handed on as-is (the case-variables read) serialises
 * back to the document the engine sent. {@code scope} is {@code local}/{@code global} or JSON null
 * (a single read, every case variable).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VariableRecord(
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String type,
        Object value,
        String scope,
        @JsonInclude(JsonInclude.Include.NON_NULL) String valueUrl) {}
