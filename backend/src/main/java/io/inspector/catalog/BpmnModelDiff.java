package io.inspector.catalog;

import io.inspector.catalog.BpmnDiffResponse.ElementChange;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Structural BPMN diff. Elements without an {@code id}, and everything outside the BPMN model namespace, are ignored. */
public final class BpmnModelDiff {

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private BpmnModelDiff() {}

    public static BpmnDiffResponse diff(
            String engineId, String leftId, String leftXml, String rightId, String rightXml) {
        Map<String, ElementView> left = elements(leftXml);
        Map<String, ElementView> right = elements(rightXml);
        List<ElementChange> added = new ArrayList<>();
        List<ElementChange> removed = new ArrayList<>();
        List<ElementChange> changed = new ArrayList<>();
        for (Map.Entry<String, ElementView> e : right.entrySet()) {
            ElementView was = left.get(e.getKey());
            if (was == null) {
                added.add(e.getValue().change("added"));
            } else if (!was.same(e.getValue())) {
                changed.add(e.getValue().change(was.detailAgainst(e.getValue())));
            }
        }
        for (Map.Entry<String, ElementView> e : left.entrySet()) {
            if (!right.containsKey(e.getKey())) {
                removed.add(e.getValue().change("removed"));
            }
        }
        return new BpmnDiffResponse(engineId, leftId, rightId, added, removed, changed);
    }

    private static Map<String, ElementView> elements(String xml) {
        Map<String, ElementView> out = new LinkedHashMap<>();
        if (xml == null || xml.isBlank()) {
            return out;
        }
        Document document = parse(xml);
        NodeList all = document.getElementsByTagNameNS(BPMN_NS, "*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String id = element.getAttribute("id");
            if (id == null || id.isBlank()) {
                continue;
            }
            out.putIfAbsent(
                    id,
                    new ElementView(
                            id,
                            element.getLocalName(),
                            blankToNull(element.getAttribute("name")),
                            blankToNull(element.getAttribute("sourceRef")),
                            blankToNull(element.getAttribute("targetRef"))));
        }
        return out;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("BPMN XML could not be parsed");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record ElementView(String id, String type, String name, String sourceRef, String targetRef) {
        boolean same(ElementView other) {
            return type.equals(other.type)
                    && java.util.Objects.equals(name, other.name)
                    && java.util.Objects.equals(sourceRef, other.sourceRef)
                    && java.util.Objects.equals(targetRef, other.targetRef);
        }

        String detailAgainst(ElementView other) {
            if (!type.equals(other.type)) {
                return "type " + type + " → " + other.type;
            }
            if (!java.util.Objects.equals(name, other.name)) {
                return "name";
            }
            return "flow";
        }

        ElementChange change(String detail) {
            return new ElementChange(id, type, name, detail);
        }
    }
}
