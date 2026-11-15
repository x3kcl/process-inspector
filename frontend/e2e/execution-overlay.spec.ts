// #677 execution overlay (SPEC §4 Stage 2, §10a): the route taken, completed and
// armed-not-taken nodes and duration badges, fed by the timeline. Hermetic: every /api call
// is fulfilled here. Model: an event-based gateway with three outcomes (Shipped / Cancelled /
// Escalated); the instance took the payment branch.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = { id: 'eng1', name: 'Orders DEV', environment: 'dev', reachable: true }
const BASE = '/api/instances/eng1/inst-done'

const VITALS = {
  compositeId: 'eng1:inst-done',
  engineId: 'eng1',
  processInstanceId: 'inst-done',
  definitionKey: 'order',
  definitionVersion: 1,
  processDefinitionId: 'order:1:abc',
  startTime: '2026-11-15T10:00:00Z',
  endTime: '2026-11-15T10:00:03Z',
  status: 'COMPLETED',
  flags: { ended: true, suspended: false, hasDeadLetterJobs: false, hasFailingJobs: false },
}

type Node = [id: string, tag: string, x: number, y: number, w: number, h: number]
const NODES: Node[] = [
  ['start', 'startEvent', 0, 112, 36, 36],
  ['recordOrder', 'serviceTask', 80, 100, 100, 60],
  ['gw', 'eventBasedGateway', 230, 105, 50, 50],
  ['catchPay', 'intermediateCatchEvent', 330, 12, 36, 36],
  ['catchCancel', 'intermediateCatchEvent', 330, 112, 36, 36],
  ['catchTimeout', 'intermediateCatchEvent', 330, 212, 36, 36],
  ['shipOrder', 'serviceTask', 420, 0, 100, 60],
  ['endShipped', 'endEvent', 580, 12, 36, 36],
  ['endCancelled', 'endEvent', 580, 112, 36, 36],
  ['endEscalated', 'endEvent', 580, 212, 36, 36],
]
const FLOWS: [id: string, source: string, target: string][] = [
  ['f_start', 'start', 'recordOrder'],
  ['f_record_gw', 'recordOrder', 'gw'],
  ['f_gw_pay', 'gw', 'catchPay'],
  ['f_gw_cancel', 'gw', 'catchCancel'],
  ['f_gw_timeout', 'gw', 'catchTimeout'],
  ['f_pay_ship', 'catchPay', 'shipOrder'],
  ['f_ship_end', 'shipOrder', 'endShipped'],
  ['f_cancel_end', 'catchCancel', 'endCancelled'],
  ['f_timeout_end', 'catchTimeout', 'endEscalated'],
]
const byId = new Map(NODES.map((node) => [node[0], node]))
const centre = (id: string) => {
  const [, , x, y, w, h] = byId.get(id) ?? ['', '', 0, 0, 0, 0]
  return `<omgdi:waypoint x="${String(x + w / 2)}" y="${String(y + h / 2)}"/>`
}
const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
  targetNamespace="http://example.com/test">
  <process id="order" isExecutable="true">
    ${NODES.map(([id, tag]) => `<${tag} id="${id}" name="${id}"/>`).join('\n    ')}
    ${FLOWS.map(([id, s, t]) => `<sequenceFlow id="${id}" sourceRef="${s}" targetRef="${t}"/>`).join('\n    ')}
  </process>
  <bpmndi:BPMNDiagram id="di">
    <bpmndi:BPMNPlane id="plane" bpmnElement="order">
      ${NODES.map(([id, , x, y, w, h]) => `<bpmndi:BPMNShape id="di-${id}" bpmnElement="${id}"><omgdc:Bounds x="${String(x)}" y="${String(y)}" width="${String(w)}" height="${String(h)}"/></bpmndi:BPMNShape>`).join('\n      ')}
      ${FLOWS.map(([id, s, t]) => `<bpmndi:BPMNEdge id="di-${id}" bpmnElement="${id}">${centre(s)}${centre(t)}</bpmndi:BPMNEdge>`).join('\n      ')}
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`

const row = (activityId: string, activityType: string, durationMs = 0) => ({
  id: `h-${activityId}`,
  activityId,
  activityName: activityId,
  activityType,
  startTime: '2026-11-15T10:00:00Z',
  endTime: '2026-11-15T10:00:01Z',
  durationMs,
})
const TAKEN = [
  'f_start',
  'f_record_gw',
  'f_gw_pay',
  'f_gw_cancel',
  'f_gw_timeout',
  'f_pay_ship',
  'f_ship_end',
]
const TIMELINE = {
  total: 15,
  truncated: false,
  activities: [
    row('start', 'startEvent'),
    row('recordOrder', 'serviceTask'),
    row('gw', 'eventBasedGateway'),
    row('catchPay', 'intermediateCatchEvent', 143),
    row('catchCancel', 'intermediateCatchEvent'),
    row('catchTimeout', 'intermediateCatchEvent'),
    row('shipOrder', 'serviceTask', 2100),
    row('endShipped', 'endEvent'),
    ...TAKEN.map((id) => row(id, 'sequenceFlow')),
  ],
}

async function mockBff(
  page: Page,
  opts: {
    timeline?: object
    active?: string[]
    deadLetter?: string[]
  },
) {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me')
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      else if (pathname === '/api/engines') await route.fulfill({ json: [ENGINE] })
      else if (pathname === BASE) await route.fulfill({ json: VITALS })
      else if (pathname === `${BASE}/variables`)
        await route.fulfill({ json: { executionScopes: [], processVariables: [] } })
      else if (pathname === `${BASE}/diagram`)
        await route.fulfill({
          json: {
            xml: XML,
            activeActivityIds: opts.active ?? [],
            deadLetterActivityIds: opts.deadLetter ?? [],
          },
        })
      else if (pathname === `${BASE}/timeline`) {
        if (opts.timeline === undefined)
          await route.fulfill({ status: 500, json: { message: 'engine down' } })
        else await route.fulfill({ json: opts.timeline })
      } else if (pathname === `${BASE}/jobs`)
        await route.fulfill({ json: { executable: [], timer: [], suspended: [], deadLetter: [] } })
      else if (pathname === '/api/bulk') await route.fulfill({ json: [] })
      else await route.fulfill({ json: {} })
    },
  )
}

const marked = (page: Page, markerClass: string) =>
  page
    .locator(`.diagram-canvas g.${markerClass}`)
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-element-id')).sort())
const glyphs = (page: Page) => page.locator('.diagram-canvas .djs-overlays [role="img"]')

test('a completed instance shows its route, outcome, armed losers and durations', async ({
  page,
}) => {
  await mockBff(page, { timeline: TIMELINE })
  await page.goto('/inspect/eng1/inst-done')
  await expect(page.locator('.diagram-canvas g.marker-taken').first()).toBeAttached()

  expect(await marked(page, 'marker-taken')).toEqual([...TAKEN].sort())
  expect(await marked(page, 'marker-completed')).toEqual(
    ['catchPay', 'endShipped', 'gw', 'recordOrder', 'shipOrder', 'start'].sort(),
  )
  expect(await marked(page, 'marker-armed')).toEqual(['catchCancel', 'catchTimeout'])

  const badge = page.getByRole('img', { name: 'step duration: 143 ms' })
  await expect(badge).toHaveText('143 ms')
  await expect(page.getByRole('img', { name: 'step duration: 2.1 s · slowest' })).toBeVisible()
  await expect(page.getByRole('img', { name: 'completed' })).toHaveCount(6)

  // Every glyph carries an accessible name.
  const names = await glyphs(page).evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute('aria-label') ?? ''),
  )
  expect(names.length).toBeGreaterThan(0)
  expect(names.every((name) => name.trim() !== '')).toBe(true)

  const legend = page.locator('.diagram-legend li')
  expect(await legend.evaluateAll((items) => items.map((li) => li.dataset.kind))).toEqual([
    'completedActivityIds',
    'armedActivityIds',
    'takenFlowIds',
    'durationActivityIds',
  ])
  await expect(page.locator('.diagram-partial')).toHaveCount(0)
  await scanA11y(page, 'diagram with execution overlay')

  // "Path off" shows the plain model.
  await page.getByRole('button', { name: 'Path off' }).click()
  await expect(page.locator('.diagram-canvas g.marker-taken')).toHaveCount(0)
  await expect(page.locator('.diagram-canvas g.marker-completed')).toHaveCount(0)
  await expect(page.locator('.diagram-canvas g.marker-armed')).toHaveCount(0)
  await expect(glyphs(page)).toHaveCount(0)
  await expect(page.locator('.diagram-legend')).toHaveCount(0)
  await scanA11y(page, 'diagram with the path hidden')

  await page.getByRole('button', { name: 'Path on' }).click()
  expect(await marked(page, 'marker-taken')).toEqual([...TAKEN].sort())
})

test('a truncated timeline says the route is incomplete', async ({ page }) => {
  await mockBff(page, { timeline: { ...TIMELINE, total: 900, truncated: true } })
  await page.goto('/inspect/eng1/inst-done')
  await expect(page.locator('.diagram-partial')).toContainText('Route incomplete')
  await expect(page.locator('.diagram-canvas g.marker-taken').first()).toBeAttached()
  await scanA11y(page, 'partial route badge')
})

test('a failed timeline leaves the active and dead-letter markers as they were', async ({
  page,
}) => {
  await mockBff(page, { active: ['shipOrder'], deadLetter: ['recordOrder'] })
  await page.goto('/inspect/eng1/inst-done')
  await expect(page.locator('.diagram-canvas g.marker-active')).toHaveCount(1)
  await expect(page.locator('.diagram-canvas g.marker-deadletter')).toHaveCount(1)
  await expect(page.getByRole('img', { name: 'dead-letter job on this activity' })).toBeVisible()
  await expect(page.locator('.diagram-canvas g.marker-taken')).toHaveCount(0)
  await expect(page.locator('.diagram-canvas g.marker-completed')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Path off' })).toHaveCount(0)
  await scanA11y(page, 'diagram without a timeline')
})
