// The definition's activity catalog for the change-state target picker. There is no JSON
// catalog endpoint — the deployed BPMN XML (InstanceDiagram.xml) is the only source of
// every flow node, mirroring the BFF's own model-vs-XML hybrid parse. A deliberately
// tolerant start-tag scan (not a DOM parse) so it works in any runtime and never throws
// on engine-generated XML; the BFF re-validates every id anyway (422 unknown-activity).

export interface CatalogActivity {
  id: string
  /** The modeler's label; falls back to the id when the node is unnamed. */
  name: string
  /** BPMN local element name, e.g. userTask, exclusiveGateway. */
  type: string
}

/** Flow nodes a token can meaningfully be canceled at or started on. The DI section
 *  (bpmndi:*) never uses these element names, so no exclusion pass is needed. */
const FLOW_NODE_TYPES = new Set([
  'task',
  'userTask',
  'serviceTask',
  'scriptTask',
  'businessRuleTask',
  'sendTask',
  'receiveTask',
  'manualTask',
  'callActivity',
  'subProcess',
  'exclusiveGateway',
  'parallelGateway',
  'inclusiveGateway',
  'eventBasedGateway',
  'startEvent',
  'endEvent',
  'intermediateCatchEvent',
  'intermediateThrowEvent',
  'boundaryEvent',
])

const START_TAG = /<(?:[\w.-]+:)?([A-Za-z]+)((?:"[^"]*"|'[^']*'|[^<>"'])*)\/?>/g

function attr(attributes: string, name: string): string | undefined {
  const match = new RegExp(`\\b${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)')`).exec(attributes)
  if (match === null) return undefined
  // Exactly one of the two alternation groups participates — the other is undefined at
  // runtime; .at() keeps that honest in the type system.
  return match.at(1) ?? match.at(2)
}

/** Extract the definition's flow nodes in document order. Empty on unparseable input. */
export function parseActivityCatalog(xml: string | undefined): CatalogActivity[] {
  if (xml === undefined || xml === '') return []
  // Comments could hide or fake tags — drop them before scanning.
  const source = xml.replace(/<!--[\s\S]*?-->/g, '')
  const catalog: CatalogActivity[] = []
  const seen = new Set<string>()
  for (const match of source.matchAll(START_TAG)) {
    // Group 1 is required and group 2 is a *-quantified group — both always participate.
    const type = match[1]
    const attributes = match[2]
    if (!FLOW_NODE_TYPES.has(type)) continue
    const id = attr(attributes, 'id')
    if (id === undefined || id === '' || seen.has(id)) continue
    seen.add(id)
    const name = attr(attributes, 'name')
    catalog.push({ id, name: name !== undefined && name !== '' ? name : id, type })
  }
  return catalog
}

/** Picker label: "Approve order (approveTask) · userTask" / "gw1 · exclusiveGateway". */
export function activityLabel(activity: CatalogActivity): string {
  const named = activity.name !== activity.id
  return named
    ? `${activity.name} (${activity.id}) · ${activity.type}`
    : `${activity.id} · ${activity.type}`
}
