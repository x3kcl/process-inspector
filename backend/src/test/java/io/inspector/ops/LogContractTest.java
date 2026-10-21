package io.inspector.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks the JSON log fields OPERATIONS §2 calls stable (#471). */
class LogContractTest {

    @Test
    void oidcEncoderKeepsTheStableMdcKeys() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"));
        assertThat(xml).contains("<includeMdcKeyName>correlationId</includeMdcKeyName>");
        assertThat(xml).contains("<includeMdcKeyName>traceId</includeMdcKeyName>");
        assertThat(xml).contains("\"application\":\"process-inspector\"");
    }
}
