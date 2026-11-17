package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the typed identity-link row (#474) against the RECORDED flowable-rest 6.8 / 7.1 documents
 * ({@code src/test/resources/wire/}), read with a bare, STRICT {@link ObjectMapper}: the tolerance of
 * {@code url} / {@code processInstanceUrl} / {@code taskUrl} is proven on the record itself. The two
 * legs name the identity differently — the runtime row says {@code user}/{@code group}, the historic
 * row {@code userId}/{@code groupId} — and the record keeps both, absent as null, so the reader's
 * first-non-blank rule stays where it was.
 */
class IdentityLinkRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private static List<IdentityLinkRecord> read(String fixture) {
        AtomicReference<IdentityLinkRecord[]> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in =
                            IdentityLinkRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, IdentityLinkRecord[].class));
                    }
                })
                .as("deserialising wire/%s into IdentityLinkRecord[]", fixture)
                .doesNotThrowAnyException();
        return List.of(out.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void runtimeInstanceLinksNameTheUserAndLeaveTheHistoricNamesNull(String major) {
        List<IdentityLinkRecord> links = read(major + "/runtime-identity-links.json");

        assertThat(links).hasSize(2);
        assertThat(links).extracting(IdentityLinkRecord::type).containsExactlyInAnyOrder("participant", "starter");
        for (IdentityLinkRecord link : links) {
            assertThat(link.user()).isEqualTo("rest-admin");
            assertThat(link.group()).isNull(); // explicit JSON null
            // the runtime leg serialises none of the historic names: absent is null
            assertThat(link.userId()).isNull();
            assertThat(link.groupId()).isNull();
            assertThat(link.processInstanceId()).isNull();
            assertThat(link.taskId()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void historicInstanceLinksNameTheUserIdAndTheInstance(String major) {
        List<IdentityLinkRecord> links = read(major + "/historic-identity-links.json");

        assertThat(links).hasSize(2);
        assertThat(links).extracting(IdentityLinkRecord::type).containsExactlyInAnyOrder("participant", "starter");
        for (IdentityLinkRecord link : links) {
            assertThat(link.userId()).isEqualTo("rest-admin");
            assertThat(link.user()).isNull(); // the historic leg has no "user" key
            assertThat(link.group()).isNull();
            assertThat(link.groupId()).isNull();
            assertThat(link.processInstanceId()).isNotBlank();
            assertThat(link.taskId()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void taskLinksCarryTheAssignee(String major) {
        List<IdentityLinkRecord> links = read(major + "/task-identity-links.json");

        assertThat(links)
                .containsExactly(new IdentityLinkRecord("rest-admin", null, null, null, "assignee", null, null));
    }
}
