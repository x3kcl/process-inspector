package io.inspector.surgery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (pure static): the guardrail structure parsed from REAL engine artifacts — the
 * demo-flow-surgery seed XML exactly as deployed, and the /model JSON exactly as
 * flowable-rest 6.8 answered it (probed 2026-07-06, stored as a fixture). No mocked wire
 * shapes: if the engine serialization changes, the fixture is re-probed, not imagined.
 */
class BpmnStructureTest {

    private static BpmnStructure structure;

    @BeforeAll
    static void parseRealArtifacts() throws IOException {
        structure = BpmnStructure.parse(modelJson(), xml());
    }

    /* ---------------- multi-instance scopes (from the /model JSON) ---------------- */

    @Test
    void everyElementOfTheMiBodyMapsToItsMiRoot() {
        assertThat(structure.multiInstanceScopeOf("miSub")).contains("miSub"); // the root maps to itself
        assertThat(structure.multiInstanceScopeOf("miTask")).contains("miSub");
        assertThat(structure.multiInstanceScopeOf("miStart")).contains("miSub");
        assertThat(structure.multiInstanceScopeOf("miEnd")).contains("miSub");
    }

    @Test
    void nodesOutsideTheMiBodyHaveNoMiScope() {
        assertThat(structure.multiInstanceScopeOf("stepOne")).isEmpty();
        assertThat(structure.multiInstanceScopeOf("branchA")).isEmpty();
        assertThat(structure.multiInstanceScopeOf("forkGw")).isEmpty();
        assertThat(structure.multiInstanceScopeOf("end")).isEmpty();
    }

    /* ---------------- node catalog (from the deployed XML) ---------------- */

    @Test
    void nodesCarryNameAndBpmnType() {
        assertThat(structure.node("stepOne")).contains(new BpmnStructure.FlowNode("stepOne", "Step one", "userTask"));
        assertThat(structure.node("forkGw")).contains(new BpmnStructure.FlowNode("forkGw", null, "parallelGateway"));
        assertThat(structure.node("miSub")).contains(new BpmnStructure.FlowNode("miSub", "MI body", "subProcess"));
        assertThat(structure.node("miTask")).isPresent(); // nested scope nodes are cataloged too
    }

    @Test
    void unknownIdsAndSequenceFlowsAreNotNodes() {
        assertThat(structure.node("doesNotExist")).isEmpty();
        assertThat(structure.node("flowOneTwo")).isEmpty();
    }

    /* ---------------- parallel-branch walk (from the deployed XML) ---------------- */

    @Test
    void branchTasksBetweenForkAndJoinAreInsideAParallelBranch() {
        assertThat(structure.insideParallelBranch("branchA")).isTrue();
        assertThat(structure.insideParallelBranch("branchB")).isTrue();
        // A token AT the join is still waiting on the branch edges — warn there too.
        assertThat(structure.insideParallelBranch("joinGw")).isTrue();
    }

    @Test
    void nodesBeforeTheForkAndAfterTheJoinAreNot() {
        assertThat(structure.insideParallelBranch("stepOne")).isFalse();
        assertThat(structure.insideParallelBranch("stepTwo")).isFalse();
        assertThat(structure.insideParallelBranch("forkGw")).isFalse();
        assertThat(structure.insideParallelBranch("miSub")).isFalse(); // the join fences it off
        assertThat(structure.insideParallelBranch("miTask")).isFalse(); // scope climb ends after the join
        assertThat(structure.insideParallelBranch("end")).isFalse();
    }

    /* ---------------- fixtures ---------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelJson() throws IOException {
        try (InputStream in = BpmnStructureTest.class.getResourceAsStream("/surgery/demo-flow-surgery-model.json")) {
            return new ObjectMapper().readValue(in, Map.class);
        }
    }

    private static String xml() throws IOException {
        try (InputStream in = BpmnStructureTest.class.getResourceAsStream("/surgery/demo-flow-surgery.bpmn20.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
