package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchRequest.VariableFilter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rung 1 (unit-test-patterns): the compiled-criteria echo + cURL are pure request→text. */
class CriteriaEchoTest {

    private static SearchRequest empty() {
        return new SearchRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void echoRendersOneLinePerCategoryWithOrSpelledOutWithin() {
        SearchRequest req = new SearchRequest(
                List.of("engine-a", "engine-b"),
                List.of(InstanceStatus.FAILED, InstanceStatus.RETRYING),
                "demoParent",
                null,
                "ord-42",
                null,
                null,
                "2026-07-06T10:00:00Z",
                null,
                "divisor",
                "review",
                List.of(new VariableFilter("customerId", "12345", null, null)),
                "failureTime",
                50);

        assertThat(CriteriaEcho.echo(req))
                .containsExactly(
                        "Engines: engine-a or engine-b",
                        "Status: FAILED or RETRYING",
                        "Definition: demoParent",
                        "Business key: contains 'ord-42'",
                        "Failed after: 2026-07-06T10:00:00Z",
                        "Variables: 'customerId' equals '12345'",
                        "Current activity: contains 'review'",
                        "Error text: contains 'divisor'",
                        "Sort: failure time (newest first)");
    }

    @Test
    void unfilteredSearchEchoesNothing() {
        assertThat(CriteriaEcho.echo(empty())).isEmpty();
    }

    @Test
    void curlReproducesTheRequestWithoutNullsAndSurvivesSingleQuotes() {
        SearchRequest req = new SearchRequest(
                null,
                List.of(InstanceStatus.FAILED),
                null,
                "O'Brien-7",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        String curl = CriteriaEcho.curl("http://inspector.local/api/search", req);

        assertThat(curl).startsWith("curl -X POST 'http://inspector.local/api/search'");
        assertThat(curl).contains("-H 'Content-Type: application/json'");
        assertThat(curl).contains("\"statuses\":[\"FAILED\"]");
        // nulls dropped: the copied command is the minimal reproduction, not a null-soup
        assertThat(curl).doesNotContain("null");
        // POSIX single-quote escaping keeps the command paste-safe
        assertThat(curl).contains("O'\\''Brien-7");
    }
}
