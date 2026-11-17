package io.inspector.client;

import static io.inspector.client.RemainingBodyWire.P;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the INSTANCE and CASE identity-link request body (#720): what the BFF sends
 * to add a participant to a process instance and to a case. Both endpoints take {@code user} and
 * {@code type} and nothing else — measured on Flowable 6.8.0 and 7.1.0 (2026-11-17): 201 for
 * {@code {"user","type"}} in either key order on both endpoints, 400 "The user is required." for
 * {@code userId}, 400 "Only user identity links are supported" for {@code group}. So unlike the
 * task body (#763) the body {@code main} sends here is the one the engines accept.
 *
 * <p>On {@code main} both bodies are a two-key {@code Map.of}, whose key order is salted per JVM:
 * the wire is compared as canonical JSON, the record as the exact string {@code user} then
 * {@code type} (the order of the engine's own representation).
 */
class IdentityLinkBodyWireShapeTest {

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
    void addingAParticipantToAProcessInstanceSendsUserAndType() {
        wire.process.addInstanceIdentityLink(wire.engine, P, "pi1", "bob", "participant");

        wire.pinsCanonical(
                "POST",
                "/runtime/process-instances/pi1/identitylinks",
                "{\"user\":\"bob\",\"type\":\"participant\"}",
                new InstanceIdentityLinkRequest("bob", "participant"));
    }

    @Test
    void addingAParticipantToACaseSendsTheSameBodyOnTheCmmnContext() {
        wire.cmmn.addCaseIdentityLink(wire.engine, P, "case1", "bob", "candidate");

        wire.pinsCanonical(
                "POST",
                "/cmmn-api/cmmn-runtime/case-instances/case1/identitylinks",
                "{\"user\":\"bob\",\"type\":\"candidate\"}",
                new InstanceIdentityLinkRequest("bob", "candidate"));
    }

    @Test
    void aLinkWithoutAUserIsRefusedBeforeAnyEngineByte() {
        // The map body threw on a null user; it never became {"type":…} or {"user":null,…}.
        assertThatThrownBy(() -> wire.process.addInstanceIdentityLink(wire.engine, P, "pi1", null, "participant"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> wire.cmmn.addCaseIdentityLink(wire.engine, P, "case1", null, "participant"))
                .isInstanceOf(NullPointerException.class);
        wire.sentNothing();
        assertThatThrownBy(() -> new InstanceIdentityLinkRequest(null, "participant"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aLinkWithoutATypeIsRefusedBeforeAnyEngineByte() {
        assertThatThrownBy(() -> wire.process.addInstanceIdentityLink(wire.engine, P, "pi1", "bob", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> wire.cmmn.addCaseIdentityLink(wire.engine, P, "case1", "bob", null))
                .isInstanceOf(NullPointerException.class);
        wire.sentNothing();
        assertThatThrownBy(() -> new InstanceIdentityLinkRequest("bob", null)).isInstanceOf(NullPointerException.class);
    }
}
