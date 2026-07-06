import { describe, expect, it } from 'vitest'
import { activityLabel, parseActivityCatalog } from './activityCatalog'

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:flowable="http://flowable.org/bpmn">
  <process id="orderFlow" name="Order flow" isExecutable="true">
    <startEvent id="theStart" name="Order received"/>
    <sequenceFlow id="flow1" sourceRef="theStart" targetRef="approveTask"/>
    <userTask id="approveTask" name="Approve order" flowable:assignee="kermit"/>
    <exclusiveGateway id="gw1"/>
    <serviceTask id='chargeTask' name='Charge card' flowable:async='true'/>
    <subProcess id="retryScope" name="Retry scope">
      <receiveTask id="waitForCallback" name="Wait for callback"/>
    </subProcess>
    <!-- <userTask id="ghostTask" name="Commented out"/> -->
    <endEvent id="theEnd" name="Done"/>
  </process>
  <bpmndi:BPMNDiagram id="diagram">
    <bpmndi:BPMNPlane id="plane" bpmnElement="orderFlow"/>
  </bpmndi:BPMNDiagram>
</definitions>`

describe('parseActivityCatalog', () => {
  it('extracts every flow node in document order, skipping sequence flows and DI', () => {
    const ids = parseActivityCatalog(XML).map((activity) => activity.id)
    expect(ids).toEqual([
      'theStart',
      'approveTask',
      'gw1',
      'chargeTask',
      'retryScope',
      'waitForCallback',
      'theEnd',
    ])
  })

  it('keeps name, id and type, falling back to the id when unnamed', () => {
    const catalog = parseActivityCatalog(XML)
    expect(catalog.find((activity) => activity.id === 'approveTask')).toEqual({
      id: 'approveTask',
      name: 'Approve order',
      type: 'userTask',
    })
    expect(catalog.find((activity) => activity.id === 'gw1')).toEqual({
      id: 'gw1',
      name: 'gw1',
      type: 'exclusiveGateway',
    })
  })

  it('handles single-quoted attributes and namespace prefixes', () => {
    const catalog = parseActivityCatalog(
      `<bpmn2:process id="p"><bpmn2:userTask id='t1' name='Quoted'/></bpmn2:process>`,
    )
    expect(catalog).toEqual([{ id: 't1', name: 'Quoted', type: 'userTask' }])
  })

  it('never resurrects commented-out nodes and dedupes repeated ids', () => {
    const ids = parseActivityCatalog(XML).map((activity) => activity.id)
    expect(ids).not.toContain('ghostTask')
    const doubled = parseActivityCatalog(
      `<process><userTask id="t1" name="A"/><userTask id="t1" name="B"/></process>`,
    )
    expect(doubled).toHaveLength(1)
  })

  it('returns empty on missing or junk input instead of throwing', () => {
    expect(parseActivityCatalog(undefined)).toEqual([])
    expect(parseActivityCatalog('')).toEqual([])
    expect(parseActivityCatalog('not xml at all')).toEqual([])
  })
})

describe('activityLabel', () => {
  it('shows name (id) · type for named nodes, id · type otherwise', () => {
    expect(activityLabel({ id: 'approveTask', name: 'Approve order', type: 'userTask' })).toBe(
      'Approve order (approveTask) · userTask',
    )
    expect(activityLabel({ id: 'gw1', name: 'gw1', type: 'exclusiveGateway' })).toBe(
      'gw1 · exclusiveGateway',
    )
  })
})
