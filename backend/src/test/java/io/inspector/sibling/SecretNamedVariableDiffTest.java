package io.inspector.sibling;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.dto.InstanceVariables.VariableDto;
import io.inspector.dto.SiblingDiffResponse.VariableDelta;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #742: SAME/CHANGED on a secret-named variable is a 1-bit oracle (diff against a sibling whose
 * value you know). The diff says MASKED instead, on both equal and unequal values, and carries
 * neither side's value. Presence (ONLY_IN_*) is not the value and stays.
 */
class SecretNamedVariableDiffTest {

    private static VariableDto row(String name, Object value) {
        return new VariableDto(name, "string", value, false, null, "global", null, null);
    }

    @Test
    void equalSecretValuesAreMaskedNotSame() {
        VariableDelta delta = SiblingDiffService.diffVariables(
                        List.of(row("password", "pw-1")), List.of(row("password", "pw-1")))
                .get(0);

        assertThat(delta.change().name()).isEqualTo("MASKED");
        assertThat(delta.subject().value()).isNull();
        assertThat(delta.sibling().value()).isNull();
    }

    @Test
    void differingSecretValuesAreMaskedNotChanged() {
        VariableDelta delta = SiblingDiffService.diffVariables(
                        List.of(row("apiToken", "a")), List.of(row("apiToken", "b")))
                .get(0);

        assertThat(delta.change().name()).isEqualTo("MASKED");
    }

    @Test
    void presenceOnOneSideIsStillReportedAndANonSecretStillCompares() {
        List<VariableDelta> deltas = SiblingDiffService.diffVariables(
                List.of(row("password", "pw-1"), row("amount", "1")), List.of(row("amount", "2")));

        assertThat(deltas)
                .extracting(d -> d.name() + "=" + d.change().name())
                .containsExactly("amount=CHANGED", "password=ONLY_IN_SUBJECT");
    }
}
