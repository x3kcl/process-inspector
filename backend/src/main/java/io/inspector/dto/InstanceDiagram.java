package io.inspector.dto;

import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/diagram — the BPMN 2.0 XML exactly as deployed
 * (proxied from the deployment's resourcedata, never re-generated) plus the marker id
 * sets the bpmn-js viewer overlays: token markers on active activities, red ⚠ badges on
 * dead-letter activities (SPEC §4 Stage 2). Marker ids are best-effort — an id the
 * diagram doesn't know (collapsed subprocess, 6.3-era jobs without elementId) simply
 * renders no marker; the XML is the contract.
 */
public record InstanceDiagram(String xml, List<String> activeActivityIds, List<String> deadLetterActivityIds) {}
