package io.inspector.action;

import static io.inspector.config.AuditProperties.TicketRequiredOn.ALL;
import static io.inspector.config.AuditProperties.TicketRequiredOn.NONE;
import static io.inspector.config.AuditProperties.TicketRequiredOn.PROD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.config.AuditProperties;
import io.inspector.config.AuditProperties.TicketRequiredOn;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import org.junit.jupiter.api.Test;

class TicketPolicyTest {

    private static TicketPolicy policy(String pattern, TicketRequiredOn required) {
        return new TicketPolicy(new AuditProperties(pattern, required, null));
    }

    @Test
    void acceptsAndNormalizesAValidTicketStrippingCrLf() {
        assertThat(policy(null, NONE).validate("  JIRA-1\n", EngineEnvironment.DEV))
                .isEqualTo("JIRA-1");
    }

    @Test
    void absentIsNullWhenNotRequired() {
        assertThat(policy(null, NONE).validate("   ", EngineEnvironment.DEV)).isNull();
        assertThat(policy(null, NONE).validate(null, EngineEnvironment.PROD)).isNull();
    }

    @Test
    void requiredOnProdRefusesAbsentOnProdButNotDev() {
        assertThat(policy(null, PROD).validate(null, EngineEnvironment.DEV)).isNull();
        assertThatThrownBy(() -> policy(null, PROD).validate(null, EngineEnvironment.PROD))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("ticket-id-required"));
    }

    @Test
    void requiredOnAllRefusesAbsentEverywhere() {
        assertThatThrownBy(() -> policy(null, ALL).validate("", EngineEnvironment.DEV))
                .isInstanceOf(GuardRefusedException.class);
    }

    @Test
    void malformedTicketIsRefusedAgainstThePattern() {
        assertThatThrownBy(() -> policy("^[A-Z]+-\\d+$", NONE).validate("not a ticket", EngineEnvironment.PROD))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("ticket-id-invalid"));
    }

    @Test
    void aWellFormedTicketPassesThePattern() {
        assertThat(policy("^[A-Z]+-\\d+$", ALL).validate("OPS-42", EngineEnvironment.PROD))
                .isEqualTo("OPS-42");
    }

    @Test
    void refusesAnOverlongTicketIdRatherThanTruncating() {
        String huge = "X".repeat(TicketPolicy.MAX_LENGTH + 1);
        assertThatThrownBy(() -> policy(null, NONE).validate(huge, EngineEnvironment.DEV))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("ticket-id-invalid"));
    }
}
