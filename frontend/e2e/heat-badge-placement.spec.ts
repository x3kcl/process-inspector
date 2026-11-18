// #785 review: a heat badge on an armed catch event (a 36 px shape) must not cover the
// event-gateway beside it nor another node's badge. Hermetic: every /api call is fulfilled here.
import { expect, test } from '@playwright/test'
import { scanA11y } from './a11y'

const DEF = 'order:3:c'
const shape = (id: string, x: number, y: number, w: number, h: number) =>
  `<bpmndi:BPMNShape id="d_${id}" bpmnElement="${id}"><omgdc:Bounds x="${String(x)}" y="${String(y)}" width="${String(w)}" height="${String(h)}"/></bpmndi:BPMNShape>`
const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" targetNamespace="t">
  <message id="m1" name="paid"/><message id="m2" name="cancelled"/>
  <process id="order" isExecutable="true">
    <eventBasedGateway id="gw"/>
    <intermediateCatchEvent id="catchA" name="Paid"><messageEventDefinition messageRef="m1"/></intermediateCatchEvent>
    <intermediateCatchEvent id="catchB" name="Cancelled"><messageEventDefinition messageRef="m2"/></intermediateCatchEvent>
    <serviceTask id="ship" name="Ship"/>
  </process>
  <bpmndi:BPMNDiagram id="di"><bpmndi:BPMNPlane id="plane" bpmnElement="order">
    ${shape('gw', 0, 125, 50, 50)}${shape('catchA', 100, 12, 36, 36)}${shape('catchB', 100, 132, 36, 36)}${shape('ship', 186, 0, 100, 60)}
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</definitions>`

const node = (activityId: string, type: string, over: object) => ({
  activityId,
  name: activityId,
  type,
  executions: 90,
  instances: 90,
  hitPct: 60,
  armedNotTaken: 60,
  avgMs: 1200,
  p50Ms: 1000,
  p95Ms: 2000,
  maxMs: 2500,
  totalMs: 108000,
  sharePct: 5,
  tier: 'COOL',
  ...over,
})
const HEAT = {
  engineId: 'eng1',
  definitionId: DEF,
  asOf: '2026-11-18T10:00:00Z',
  cached: false,
  coverage: 'FULL',
  structure: 'RESOLVED',
  requestedN: 200,
  sampledInstances: 150,
  finishedTotal: 150,
  droppedInstances: 0,
  dropReasons: { tooManyRows: 0, readFailed: 0, noActivityHistory: 0 },
  partial: false,
  nodes: [
    node('gw', 'eventBasedGateway', { armedNotTaken: 0, tier: 'NONE' }),
    node('catchA', 'intermediateCatchEvent', {}),
    node('catchB', 'intermediateCatchEvent', { instances: 60, executions: 60, armedNotTaken: 90 }),
    node('ship', 'serviceTask', { armedNotTaken: 0, tier: 'DOMINANT', sharePct: 80 }),
  ],
  outcomes: [],
  noEndEventCount: 150,
  samplingNote: 'newest 150 finished instances',
}

test('an armed catch event badge clears the gateway beside it and the next badge', async ({
  page,
}) => {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me')
        await route.fulfill({ json: { role: 'VIEWER', engineRoles: { eng1: 'VIEWER' } } })
      else if (pathname === '/api/engines')
        await route.fulfill({
          json: [{ id: 'eng1', name: 'Orders DEV', environment: 'dev', reachable: true }],
        })
      else if (pathname === '/api/bulk') await route.fulfill({ json: [] })
      else if (pathname.endsWith('/heat')) await route.fulfill({ json: HEAT })
      else if (pathname.endsWith('/xml')) await route.fulfill({ json: { xml: XML } })
      else if (pathname === `/api/definitions/eng1/${DEF}`)
        await route.fulfill({
          json: {
            key: 'order',
            version: 3,
            kind: 'BPMN',
            versions: [{ definitionId: DEF, version: 3 }],
          },
        })
      else await route.fulfill({ json: {} })
    },
  )
  await page.goto(`/definitions/eng1/${DEF}`)
  await page.getByRole('tab', { name: 'heat' }).click()
  const badgeA = page.getByRole('img', { name: /heat, cool: .*armed in 150, taken in 90/ })
  const badgeB = page.getByRole('img', { name: /heat, cool: .*armed in 150, taken in 60/ })
  const shipBadge = page.getByRole('img', { name: /heat, dominant/ })
  await expect(badgeA).toBeVisible()
  const box = async (locator: typeof badgeA) => {
    const b = await locator.boundingBox()
    if (b === null) throw new Error('no box')
    return b
  }
  const overlaps = (
    a: { x: number; y: number; width: number; height: number },
    b: { x: number; y: number; width: number; height: number },
  ) => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
  const gw = await box(page.locator('[data-element-id="gw"]'))
  const ship = await box(page.locator('[data-element-id="ship"]'))
  for (const badge of [badgeA, badgeB]) {
    const b = await box(badge)
    expect(overlaps(b, gw), 'event badge covers the gateway').toBe(false)
    expect(overlaps(b, ship), 'event badge covers the next task').toBe(false)
    expect(overlaps(b, await box(shipBadge)), 'event badge covers the task badge').toBe(false)
  }
  await scanA11y(page, 'heat badges on an event gateway')
})
