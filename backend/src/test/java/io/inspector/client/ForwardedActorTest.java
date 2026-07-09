package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Rung 1: the per-thread forwarded-actor holder + its sanitization (M4-CLOSEOUT §2 / D2b). */
class ForwardedActorTest {

    @AfterEach
    void tearDown() {
        ForwardedActor.clear();
    }

    @Test
    void setThenCurrentRoundTrips() {
        ForwardedActor.set("alice");
        assertThat(ForwardedActor.current()).isEqualTo("alice");
    }

    @Test
    void clearRemovesTheValue() {
        ForwardedActor.set("alice");
        ForwardedActor.clear();
        assertThat(ForwardedActor.current()).isNull();
    }

    @Test
    void blankOrNullActorCollapsesToNull() {
        ForwardedActor.set("alice");
        ForwardedActor.set("   ");
        assertThat(ForwardedActor.current()).isNull();

        ForwardedActor.set("bob");
        ForwardedActor.set(null);
        assertThat(ForwardedActor.current()).isNull();
    }

    @Test
    void stripsCrlfAndControlCharsToDefeatHeaderInjection() {
        ForwardedActor.set("al\r\nice\tX-Evil: 1");
        // CR/LF/TAB gone → no way to smuggle a second header line into the outbound request.
        assertThat(ForwardedActor.current()).isEqualTo("aliceX-Evil: 1");
    }

    @Test
    void capsLength() {
        String longActor = "a".repeat(ForwardedActor.MAX_LENGTH + 50);
        ForwardedActor.set(longActor);
        assertThat(ForwardedActor.current()).hasSize(ForwardedActor.MAX_LENGTH);
    }

    @Test
    void sanitizeIsPureAndNullSafe() {
        assertThat(ForwardedActor.sanitize(null)).isNull();
        assertThat(ForwardedActor.sanitize("  bob  ")).isEqualTo("bob");
        assertThat(ForwardedActor.sanitize("\r\n")).isNull();
    }
}
