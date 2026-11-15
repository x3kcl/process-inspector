// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { executionOverlay } from './executionOverlay'

// #677 — the execution overlay model: timeline rows + BPMN XML → route, completed,
// armed-not-taken, durations, partial.

const flow = (id: string, source: string, target: string) =>
  `<bpmn:sequenceFlow id="${id}" sourceRef="${source}" targetRef="${target}"/>`
const xmlOf = (...flows: string[]) =>
  `<?xml version="1.0"?><bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"><bpmn:process id="p">${flows.join('')}</bpmn:process></bpmn:definitions>`

const END = '2026-11-15T10:00:01Z'
const node = (activityId: string, activityType: string, durationMs = 0, ended = true) => ({
  activityId,
  activityType,
  durationMs,
  startTime: '2026-11-15T10:00:00Z',
  ...(ended ? { endTime: END } : {}),
})
const taken = (activityId: string) => node(activityId, 'sequenceFlow')

describe('executionOverlay', () => {
  it('linear run: every flow taken once, every node completed, end event included', () => {
    const xml = xmlOf(flow('f1', 'start', 'task'), flow('f2', 'task', 'end'))
    const overlay = executionOverlay(
      {
        activities: [
          node('start', 'startEvent'),
          taken('f1'),
          node('task', 'serviceTask', 5),
          taken('f2'),
          node('end', 'endEvent'),
        ],
      },
      xml,
      [],
    )
    expect([...overlay.takenFlows]).toEqual([
      ['f1', 1],
      ['f2', 1],
    ])
    expect(overlay.completed).toEqual(['start', 'task', 'end'])
    expect(overlay.armedNotTaken).toEqual([])
    expect(overlay.partial).toBe(false)
  })

  it('exclusive gateway: only the taken branch is on the route', () => {
    const xml = xmlOf(
      flow('fIn', 'start', 'gw'),
      flow('fYes', 'gw', 'yes'),
      flow('fNo', 'gw', 'no'),
      flow('fYesEnd', 'yes', 'end'),
    )
    const overlay = executionOverlay(
      {
        activities: [
          node('start', 'startEvent'),
          taken('fIn'),
          node('gw', 'exclusiveGateway'),
          taken('fYes'),
          node('yes', 'userTask', 20),
          taken('fYesEnd'),
          node('end', 'endEvent'),
        ],
      },
      xml,
      [],
    )
    expect([...overlay.takenFlows.keys()]).toEqual(['fIn', 'fYes', 'fYesEnd'])
    expect(overlay.takenFlows.has('fNo')).toBe(false)
    expect(overlay.completed).toEqual(['start', 'gw', 'yes', 'end'])
  })

  it('event-based gateway: the winning catch event completed, the two others armed-not-taken', () => {
    const xml = xmlOf(
      flow('fPay', 'gw', 'catchPay'),
      flow('fCancel', 'gw', 'catchCancel'),
      flow('fTimeout', 'gw', 'catchTimeout'),
      flow('fPayEnd', 'catchPay', 'endShipped'),
      flow('fCancelEnd', 'catchCancel', 'endCancelled'),
      flow('fTimeoutEnd', 'catchTimeout', 'endEscalated'),
    )
    const overlay = executionOverlay(
      {
        activities: [
          node('gw', 'eventBasedGateway'),
          taken('fPay'),
          taken('fCancel'),
          taken('fTimeout'),
          node('catchPay', 'intermediateCatchEvent', 143),
          node('catchCancel', 'intermediateCatchEvent', 143),
          node('catchTimeout', 'intermediateCatchEvent', 141),
          taken('fPayEnd'),
          node('endShipped', 'endEvent', 1),
        ],
      },
      xml,
      [],
    )
    expect(overlay.completed).toEqual(['gw', 'catchPay', 'endShipped'])
    expect(overlay.armedNotTaken).toEqual(['catchCancel', 'catchTimeout'])
  })

  it('loop: a flow taken twice counts 2 and the looping node sums its durations', () => {
    const xml = xmlOf(flow('fBack', 'retry', 'retry'), flow('fOut', 'retry', 'end'))
    const overlay = executionOverlay(
      {
        activities: [
          node('retry', 'serviceTask', 10),
          taken('fBack'),
          node('retry', 'serviceTask', 15),
          taken('fBack'),
          node('retry', 'serviceTask', 5),
          taken('fOut'),
          node('end', 'endEvent', 2),
        ],
      },
      xml,
      [],
    )
    expect(overlay.takenFlows.get('fBack')).toBe(2)
    expect(overlay.takenFlows.get('fOut')).toBe(1)
    expect(overlay.durations.get('retry')).toBe(30)
    expect(overlay.completed).toEqual(['retry', 'end'])
  })

  it('running instance: the active node is not completed even with an ended earlier visit', () => {
    const xml = xmlOf(flow('f1', 'start', 'task'), flow('f2', 'task', 'end'))
    const overlay = executionOverlay(
      {
        activities: [node('start', 'startEvent'), taken('f1'), node('task', 'userTask', 0, false)],
      },
      xml,
      ['task'],
    )
    expect(overlay.completed).toEqual(['start'])
    expect(overlay.armedNotTaken).toEqual([])
    expect(overlay.completed).not.toContain('task')
  })

  it('durations: sub-ms rows unbadged; slowest flagged only with two or more badges', () => {
    const xml = xmlOf(flow('f1', 'a', 'b'), flow('f2', 'b', 'c'))
    const rows = [
      node('a', 'serviceTask', 0.4),
      node('b', 'serviceTask', 143),
      node('c', 'endEvent', 1),
    ]
    const two = executionOverlay({ activities: rows }, xml, [])
    expect([...two.durations]).toEqual([
      ['b', 143],
      ['c', 1],
    ])
    expect(two.slowestId).toBe('b')

    const one = executionOverlay({ activities: rows.slice(0, 2) }, xml, [])
    expect([...one.durations]).toEqual([['b', 143]])
    expect(one.slowestId).toBeUndefined()
  })

  it('partial: a truncated timeline or any capped row', () => {
    const xml = xmlOf(flow('f1', 'a', 'b'))
    expect(executionOverlay({ activities: [], truncated: true }, xml, []).partial).toBe(true)
    expect(
      executionOverlay({ activities: [{ ...node('a', 'callActivity'), isCapped: true }] }, xml, [])
        .partial,
    ).toBe(true)
    expect(executionOverlay({ activities: [node('a', 'task')] }, xml, []).partial).toBe(false)
  })

  it('unparseable XML: ended rows fall back to completed, never armed-not-taken', () => {
    const overlay = executionOverlay(
      { activities: [node('gw', 'eventBasedGateway'), node('catchA', 'intermediateCatchEvent')] },
      '<definitions><not closed',
      [],
    )
    expect(overlay.completed).toEqual(['gw', 'catchA'])
    expect(overlay.armedNotTaken).toEqual([])
  })
})
