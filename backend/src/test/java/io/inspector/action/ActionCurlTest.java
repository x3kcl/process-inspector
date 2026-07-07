package io.inspector.action;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Rung 1 for the "Show as cURL" honesty renderer: the command targets the BFF endpoint with a
 * PLACEHOLDER credential (never a live token), drops null fields, and POSIX-escapes the
 * single-quoted body so a value carrying a quote can't break out of {@code -d '…'}.
 */
class ActionCurlTest {

    private static final String URL = "https://inspector.example/api/instances/engine-a/pi-1/actions/reassign-task";

    @Test
    void rendersTheBffEndpointWithAPlaceholderCredentialNeverASecret() {
        String curl = ActionCurl.render(URL, reassign("gonzo"));

        assertThat(curl)
                .startsWith("curl -X POST '" + URL + "'")
                .contains("-H 'Content-Type: application/json'")
                .contains("-H 'Authorization: Basic <your-credentials>'")
                .doesNotContain("password")
                .doesNotContain("Bearer ");
    }

    @Test
    void bodyCarriesOnlyTheNonNullFields() {
        String curl = ActionCurl.render(URL, reassign("gonzo"));

        assertThat(curl).contains("-d '{\"taskId\":\"task-9\",\"assignee\":\"gonzo\"}'");
        // reason/ticketId/etc. were null — they must not appear as \"...\":null noise
        assertThat(curl).doesNotContain("\"reason\":").doesNotContain("null");
    }

    @Test
    void aSingleQuoteInAValueIsPosixEscapedNotLeftToBreakOutOfTheBody() {
        String curl = ActionCurl.render(URL, reassign("o'brien"));

        // JSON keeps the raw apostrophe; the shell layer wraps it as '\'' so the -d '…' stays intact.
        assertThat(curl).contains("\"assignee\":\"o'\\''brien\"");
    }

    @Test
    void anEmptyRequestStillRendersAWellFormedCommand() {
        String curl = ActionCurl.render(URL, ActionRequest.empty());
        assertThat(curl).contains("-d '{}'");
    }

    private static ActionRequest reassign(String assignee) {
        return new ActionRequest(null, null, null, null, "task-9", null, null, null, null, null, assignee);
    }
}
