package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the EXECUTION ACTION request bodies (#720): what the BFF PUTs on
 * {@code /runtime/executions/{id}} to deliver what a waiting execution is blocked on
 * (UNSTICK_EVENT) — a message, a signal, or a plain trigger. Each body carries {@code action} and
 * at most ONE name key; the other name key is absent, never {@code null}.
 *
 * <p>On {@code main} the message and signal bodies are a two-key {@code Map.of}, whose key order
 * is salted per JVM: their wire is compared as canonical JSON, the record as the exact string
 * {@code action} then the name. The trigger body has one key and is compared as the exact string.
 */
class ExecutionActionBodyWireShapeTest {

    private RemainingBodyWire wire;

    @BeforeEach
    void setUp() {
        wire = new RemainingBodyWire();
    }

    @AfterEach
    void tearDown() {
        wire.close();
    }

    @Test
    void aMessageEventSendsTheActionAndTheMessageNameAndNoSignalName() {
        wire.messageEvent("ex1", "paymentReceived");

        wire.pinsCanonical(
                "PUT",
                "/runtime/executions/ex1",
                "{\"action\":\"messageEventReceived\",\"messageName\":\"paymentReceived\"}",
                ExecutionActionRequest.message("paymentReceived"));
    }

    @Test
    void aSignalEventSendsTheActionAndTheSignalNameAndNoMessageName() {
        wire.signalEvent("ex1", "alert");

        wire.pinsCanonical(
                "PUT",
                "/runtime/executions/ex1",
                "{\"action\":\"signalEventReceived\",\"signalName\":\"alert\"}",
                ExecutionActionRequest.signal("alert"));
    }

    @Test
    void aTriggerSendsTheActionAndNothingElse() {
        wire.trigger("ex1");

        wire.pins("PUT", "/runtime/executions/ex1", "{\"action\":\"trigger\"}", ExecutionActionRequest.trigger());
    }

    @Test
    void anEventWithoutANameIsRefusedBeforeAnyEngineByte() {
        // The map body threw on a null name; it never became {"action":…} alone (which the engine
        // would read as a different request) or {"messageName":null}.
        assertThatThrownBy(() -> wire.messageEvent("ex1", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> wire.signalEvent("ex1", null)).isInstanceOf(NullPointerException.class);
        wire.sentNothing();
        assertThatThrownBy(() -> ExecutionActionRequest.message(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExecutionActionRequest.signal(null)).isInstanceOf(NullPointerException.class);
    }
}
