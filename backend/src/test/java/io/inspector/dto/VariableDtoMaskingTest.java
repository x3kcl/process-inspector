package io.inspector.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.dto.InstanceVariables.VariableDto;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #742 choke point: building a {@link VariableDto} for a secret-named variable cannot produce a
 * value on the wire — whoever builds it. The type stays; the size and truncation facts go (they
 * leak the length). Name-based, top-level only: a {@code config} json holding a {@code password}
 * key is NOT masked (the documented limit).
 */
class VariableDtoMaskingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode wire(VariableDto dto) {
        return mapper.valueToTree(dto);
    }

    @Test
    void aSecretNamedRowIsMaskedWhoeverBuildsIt() {
        JsonNode json = wire(new VariableDto("clientSecret", "string", "cs-1", false, 4L, "global", null, null));

        assertThat(json.path("masked").asBoolean()).isTrue();
        assertThat(json.has("value")).isFalse();
        assertThat(json.has("sizeBytes")).isFalse();
        assertThat(json.path("truncated").asBoolean()).isFalse();
        assertThat(json.path("type").asText()).isEqualTo("string");
        assertThat(json.toString()).doesNotContain("cs-1");
    }

    @Test
    void aTruncatedSecretDoesNotLeakItsSizeEither() {
        JsonNode json = wire(new VariableDto("apiToken", "json", null, true, 999_999L, "global", null, null));

        assertThat(json.path("masked").asBoolean()).isTrue();
        assertThat(json.path("truncated").asBoolean()).isFalse();
        assertThat(json.has("sizeBytes")).isFalse();
    }

    @Test
    void aNonSecretRowIsUntouched() {
        JsonNode json = wire(new VariableDto("amount", "integer", 42, false, null, "global", null, null));

        assertThat(json.path("masked").asBoolean()).isFalse();
        assertThat(json.path("value").asInt()).isEqualTo(42);
    }

    @Test
    void nestedSecretKeysAreNotMaskedNameBasedTopLevelOnly() {
        JsonNode json = wire(
                new VariableDto("config", "json", Map.of("password", "nested-pw"), false, null, "global", null, null));

        assertThat(json.path("masked").asBoolean()).isFalse();
        assertThat(json.path("value").path("password").asText()).isEqualTo("nested-pw");
    }
}
