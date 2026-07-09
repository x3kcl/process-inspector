package io.inspector.surgery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The parsed structure of ONE deployed process definition — everything the change-state
 * guardrails need to know about the model, and nothing an engine call can answer live.
 *
 * Built from TWO representations of the same immutable definition (probed on
 * flowable-rest 6.8, 2026-07-06):
 *
 * <ul>
 *   <li><b>The engine's {@code /model} JSON</b> (Jackson-serialized BpmnModel) — the
 *       authoritative multi-instance signal: every element entry carries
 *       {@code loopCharacteristics}, including subprocess roots whose nested
 *       {@code flowElementMap} enumerates the body.
 *   <li><b>The deployed BPMN XML</b> ({@code /resourcedata}) — gateway TYPES are not
 *       distinguishable in the /model JSON (a parallelGateway serializes field-identical
 *       to an exclusiveGateway), so parallel-gateway analysis, element names/types and
 *       the sequence-flow graph come from the XML, where {@code <parallelGateway>} is
 *       unambiguous.
 * </ul>
 */
public final class BpmnStructure {

    private static final String MODEL_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** Container elements whose children are flow nodes of a NESTED scope. */
    private static final Set<String> SCOPE_TYPES = Set.of("subProcess", "transaction", "adHocSubProcess");

    /** Model-namespace children that are not flow nodes despite carrying an id. */
    private static final Set<String> NON_NODE_TYPES = Set.of(
            "sequenceFlow",
            "laneSet",
            "lane",
            "dataObject",
            "dataObjectReference",
            "dataStoreReference",
            "textAnnotation",
            "association",
            "group");

    /** A flow node as the operator sees it: id, display name, BPMN element type. */
    public record FlowNode(String id, String name, String type) {
        public String label() {
            return (name != null && !name.isBlank() ? "'" + name + "'" : "") + " (" + type + " " + id + ")";
        }
    }

    private final Map<String, FlowNode> nodes;
    private final Map<String, String> miScopeOf; // element id -> its multi-instance root id
    private final Set<String> parallelForks;
    private final Set<String> parallelJoins;
    private final Map<String, List<String>> incoming; // node id -> source node ids (same scope)
    private final Map<String, String> scopeOf; // node id -> enclosing subprocess id (null = process root)

    private BpmnStructure(
            Map<String, FlowNode> nodes,
            Map<String, String> miScopeOf,
            Set<String> parallelForks,
            Set<String> parallelJoins,
            Map<String, List<String>> incoming,
            Map<String, String> scopeOf) {
        this.nodes = nodes;
        this.miScopeOf = miScopeOf;
        this.parallelForks = parallelForks;
        this.parallelJoins = parallelJoins;
        this.incoming = incoming;
        this.scopeOf = scopeOf;
    }

    public Optional<FlowNode> node(String activityId) {
        return Optional.ofNullable(nodes.get(activityId));
    }

    /** Every flow node in the definition, in document order — the target-activity catalog the
     * migration mapping dropdown offers for a flagged source activity. */
    public java.util.Collection<FlowNode> flowNodes() {
        return nodes.values();
    }

    /** The multi-instance root whose body contains {@code activityId} (the root maps to itself). */
    public Optional<String> multiInstanceScopeOf(String activityId) {
        return Optional.ofNullable(miScopeOf.get(activityId));
    }

    /**
     * The nesting path of {@code activityId} from the process root inward: the ordered list
     * of enclosing subprocess ids (outermost first), empty for a node at the process root.
     * Used by instance-migration's structural diff to warn when an activity keeps its id but
     * moves into/out of a subprocess scope between versions — a re-nesting the engine's
     * auto-mapper silently accepts but which changes variable scoping and event context.
     */
    public List<String> nestingPath(String activityId) {
        Deque<String> stack = new ArrayDeque<>();
        for (String scope = scopeOf.get(activityId); scope != null; scope = scopeOf.get(scope)) {
            stack.push(scope); // push parents so the outermost ends up first
        }
        return new ArrayList<>(stack);
    }

    /**
     * Is {@code activityId} inside an unjoined parallel-gateway branch? Backward walk over
     * incoming sequence flows: reaching a diverging parallel gateway before crossing a
     * converging one means sibling branches hold (or will expect) concurrent tokens.
     * Climbs out of (non-MI) subprocess scopes so a node nested inside a subprocess that
     * itself sits on a parallel branch still warns. Heuristic by design — it powers a
     * WARNING, never a block.
     */
    public boolean insideParallelBranch(String activityId) {
        for (String start = activityId; start != null; start = scopeOf.get(start)) {
            if (backwardWalkReachesFork(start)) {
                return true;
            }
        }
        return false;
    }

    private boolean backwardWalkReachesFork(String startId) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (parallelForks.contains(current) && !current.equals(startId)) {
                return true;
            }
            if (parallelJoins.contains(current) && !current.equals(startId)) {
                continue; // the branch region ended here — anything before the join is outside it
            }
            queue.addAll(incoming.getOrDefault(current, List.of()));
        }
        return false;
    }

    /* --------------------------------- parsing --------------------------------- */

    public static BpmnStructure parse(Map<String, Object> modelJson, String bpmnXml) {
        Map<String, String> miScopeOf = new LinkedHashMap<>();
        scanModelJsonForMultiInstance(modelJson, miScopeOf);

        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        Set<String> parallelGateways = new HashSet<>();
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        Map<String, Integer> outgoingCount = new LinkedHashMap<>();
        Map<String, Integer> incomingCount = new LinkedHashMap<>();
        Map<String, String> scopeOf = new LinkedHashMap<>();

        Document doc = parseXml(bpmnXml);
        NodeList processes = doc.getElementsByTagNameNS(MODEL_NS, "process");
        for (int i = 0; i < processes.getLength(); i++) {
            walkScope(
                    (Element) processes.item(i),
                    null,
                    nodes,
                    parallelGateways,
                    incoming,
                    outgoingCount,
                    incomingCount,
                    scopeOf);
        }

        Set<String> forks = new HashSet<>();
        Set<String> joins = new HashSet<>();
        for (String gw : parallelGateways) {
            if (outgoingCount.getOrDefault(gw, 0) > 1) {
                forks.add(gw);
            }
            if (incomingCount.getOrDefault(gw, 0) > 1) {
                joins.add(gw);
            }
        }
        return new BpmnStructure(nodes, miScopeOf, forks, joins, incoming, scopeOf);
    }

    /**
     * The /model JSON walk (the mandated MI source): an element with a non-null
     * {@code loopCharacteristics} is an MI root; every id inside its nested
     * {@code flowElementMap} belongs to that root's body, recursively.
     */
    @SuppressWarnings("unchecked")
    private static void scanModelJsonForMultiInstance(Map<String, Object> container, Map<String, String> miScopeOf) {
        Object processes = container.get("processes");
        if (processes instanceof List<?> list) {
            for (Object process : list) {
                if (process instanceof Map<?, ?> map) {
                    scanFlowElementMap((Map<String, Object>) map, null, miScopeOf);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void scanFlowElementMap(
            Map<String, Object> container, String currentMiRoot, Map<String, String> miScopeOf) {
        Object flowElementMap = container.get("flowElementMap");
        if (!(flowElementMap instanceof Map<?, ?> elements)) {
            return;
        }
        for (Map.Entry<?, ?> entry : elements.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> element)) {
                continue;
            }
            String id = String.valueOf(entry.getKey());
            Map<String, Object> el = (Map<String, Object>) element;
            String miRoot = el.get("loopCharacteristics") != null ? id : currentMiRoot;
            if (miRoot != null) {
                miScopeOf.putIfAbsent(id, miRoot);
            }
            scanFlowElementMap(el, miRoot, miScopeOf);
        }
    }

    private static void walkScope(
            Element scope,
            String scopeId,
            Map<String, FlowNode> nodes,
            Set<String> parallelGateways,
            Map<String, List<String>> incoming,
            Map<String, Integer> outgoingCount,
            Map<String, Integer> incomingCount,
            Map<String, String> scopeOf) {
        for (Node child = scope.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element el) || !MODEL_NS.equals(el.getNamespaceURI())) {
                continue;
            }
            String type = el.getLocalName();
            String id = el.getAttribute("id");
            if ("sequenceFlow".equals(type)) {
                String source = el.getAttribute("sourceRef");
                String target = el.getAttribute("targetRef");
                incoming.computeIfAbsent(target, k -> new ArrayList<>()).add(source);
                outgoingCount.merge(source, 1, Integer::sum);
                incomingCount.merge(target, 1, Integer::sum);
                continue;
            }
            if (id.isBlank() || NON_NODE_TYPES.contains(type)) {
                continue;
            }
            nodes.put(id, new FlowNode(id, blankToNull(el.getAttribute("name")), type));
            if (scopeId != null) {
                scopeOf.put(id, scopeId);
            }
            if ("parallelGateway".equals(type)) {
                parallelGateways.add(id);
            }
            if (SCOPE_TYPES.contains(type)) {
                walkScope(el, id, nodes, parallelGateways, incoming, outgoingCount, incomingCount, scopeOf);
            }
        }
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // The XML comes from a registered engine, but harden against XXE regardless.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Deployed BPMN XML is not parseable: " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
