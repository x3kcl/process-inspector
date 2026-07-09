package io.inspector.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Boot-time fail-fast validation of the R-AUD-07 audit knobs (AuditProperties compact ctor). */
class AuditPropertiesTest {

    @Test
    void acceptsAnHttpsTemplateWithExactlyOneToken() {
        assertThatCode(() -> new AuditProperties(null, null, "https://jira.example/browse/{ticketId}"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsANonHttpScheme() {
        assertThatThrownBy(() -> new AuditProperties(null, null, "javascript:alert('{ticketId}')"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingOrDuplicatedToken() {
        assertThatThrownBy(() -> new AuditProperties(null, null, "https://jira.example/browse/X"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditProperties(null, null, "https://jira.example/{ticketId}/{ticketId}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnUncompilableTicketIdPattern() {
        assertThatThrownBy(() -> new AuditProperties("[unclosed", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsNullsAsAllDefaults() {
        AuditProperties props = new AuditProperties(null, null, null);
        assertThatCode(() -> props.ticketRequiredOnOrDefault()).doesNotThrowAnyException();
    }
}
