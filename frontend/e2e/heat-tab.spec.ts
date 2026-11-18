// #785 definition Heat tab: text badges by tier, node table synced to the diagram, funnel and the
// honesty text. Hermetic: every /api call is fulfilled here. axe in light AND dark.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = { id: 'eng1', name: 'Orders DEV', environment: 'dev', reachable: true }
const DEF = 'order:2:b'
const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" targetNamespace="t">
  <process id="order" isExecutable="true">
    <startEvent id="start" name="Start"/>
    <userTask id="approve" name="Approve"/>
    <serviceTask id="ship" name="Ship"/>
    <endEvent id="endOk" name="Approved"/>
  </process>
  <bpmndi:BPMNDiagram id="di"><bpmndi:BPMNPlane id="plane" bpmnElement="order">
    <bpmndi:BPMNShape id="s1" bpmnElement="start"><omgdc:Bounds x="0" y="12" width="36" height="36"/></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="s2" bpmnElement="approve"><omgdc:Bounds x="80" y="0" width="120" height="60"/></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="s3" bpmnElement="ship"><omgdc:Bounds x="250" y="0" width="120" height="60"/></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="s4" bpmnElement="endOk"><omgdc:Bounds x="420" y="12" width="36" height="36"/></bpmndi:BPMNShape>
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</definitions>`

const node = (activityId: string, name: string, type: string, over: object) => ({
  activityId,
  name,
  type,
  executions: 100,
  instances: 100,
  hitPct: 100,
  armedNotTaken: 0,
  avgMs: 1200,
  p50Ms: 1000,
  p95Ms: 2000,
  maxMs: 2500,
  totalMs: 120000,
  sharePct: 50,
  tier: 'DOMINANT',
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
  finishedTotal: 1000,
  droppedInstances: 2,
  dropReasons: { tooManyRows: 1, readFailed: 1, noActivityHistory: 0 },
  partial: true,
  nodes: [
    node('start', 'Start', 'startEvent', { avgMs: 0, tier: 'NONE' }),
    node('approve', 'Approve', 'userTask', { hitPct: 62 }),
    node('ship', 'Ship', 'serviceTask', { hitPct: 30, tier: 'COOL', sharePct: 3 }),
  ],
  outcomes: [{ endEventId: 'endOk', name: 'Approved', count: 140, pct: 93.3 }],
  noEndEventCount: 10,
  samplingNote: 'newest 150 finished instances',
}

async function mount(page: Page, theme: 'light' | 'dark'): Promise<void> {
  await page.addInitScript((t) => {
    localStorage.setItem('inspector.theme', t)
  }, theme)
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me')
        await route.fulfill({ json: { role: 'VIEWER', engineRoles: { eng1: 'VIEWER' } } })
      else if (pathname === '/api/engines') await route.fulfill({ json: [ENGINE] })
      else if (pathname === '/api/bulk') await route.fulfill({ json: [] })
      else if (pathname.endsWith('/heat')) await route.fulfill({ json: HEAT })
      else if (pathname.endsWith('/xml')) await route.fulfill({ json: { xml: XML } })
      else if (pathname === `/api/definitions/eng1/${DEF}`)
        await route.fulfill({
          json: {
            key: 'order',
            version: 2,
            kind: 'BPMN',
            suspended: false,
            versions: [{ definitionId: DEF, version: 2, latest: true }],
          },
        })
      else await route.fulfill({ json: {} })
    },
  )
  await page.goto(`/definitions/eng1/${DEF}`)
  await page.getByRole('tab', { name: 'heat' }).click()
}

for (const theme of ['light', 'dark'] as const) {
  test(`heat tab, ${theme}: text badges, honesty, synced table, axe`, async ({ page }) => {
    await mount(page, theme)
    await expect(page.getByText(/sampled 150 of 1,000 finished instances/)).toBeVisible()
    await expect(page.getByText('partial', { exact: true })).toBeVisible()
    await expect(page.getByText(/2 dropped/)).toBeVisible()
    const badge = page.getByRole('img', { name: /heat, dominant: avg 1\.2 s · 62 % · dominant/ })
    await expect(badge).toBeVisible()
    await expect(page.getByRole('list', { name: 'Outcome funnel' })).toContainText('Approved')
    await page.getByRole('button', { name: 'Show Ship on diagram' }).click()
    await expect(page.locator('[data-element-id="ship"].marker-selected')).toHaveCount(1)
    await expect(page.getByRole('row', { name: /Ship/ })).toHaveAttribute('aria-selected', 'true')
    await scanA11y(page, `definition heat tab, ${theme}`)
  })
}
