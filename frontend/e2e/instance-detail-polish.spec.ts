// #690 instance-detail polish: the collapsed Operations drawer must not cover the tablist in the
// first view, the diagram toolbar/legend are inset from the viewport edge, and the global engine
// strip collapses to one line on detail routes. Hermetic (mocked BFF), layout facts not snapshots.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINES = ['eng1', 'eng2', 'eng3'].map((id) => ({
  id,
  name: `Engine ${id}`,
  environment: 'dev',
  mode: 'FULL',
  reachable: true,
  engineVersion: '7.1.0',
  jobLanes: { executable: 1, timer: 2, suspended: 0, deadletter: 0, history: 0 },
  capabilities: { changeState: true, migration: false },
}))

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" targetNamespace="smoke">
  <process id="orderFlow" name="Order flow" isExecutable="true">
    <startEvent id="theStart" name="Order received"/>
    <userTask id="approveTask" name="Approve order"/>
    <endEvent id="theEnd" name="Done"/>
  </process>
  <bpmndi:BPMNDiagram id="di">
    <bpmndi:BPMNPlane id="plane" bpmnElement="orderFlow">
      <bpmndi:BPMNShape id="s1" bpmnElement="theStart"><omgdc:Bounds x="0" y="0" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s2" bpmnElement="approveTask"><omgdc:Bounds x="80" y="0" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s4" bpmnElement="theEnd"><omgdc:Bounds x="220" y="0" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`

const VITALS = {
  engineId: 'eng1',
  processInstanceId: 'inst-1',
  compositeId: 'eng1:inst-1',
  businessKey: 'order-4711',
  status: 'ACTIVE',
  flags: { ended: false, suspended: false },
  definitionKey: 'orderFlow',
  definitionName: 'Order flow',
  definitionVersion: 3,
  processDefinitionId: 'orderFlow:3:abc',
  startTime: '2026-07-01T08:00:00Z',
  currentActivities: [
    { activityId: 'approveTask', activityName: 'Approve order', activityType: 'userTask' },
  ],
  waitingFor: [],
}

async function mockBff(page: Page): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'ADMIN', engineRoles: { eng1: 'ADMIN' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: ENGINES })
      } else if (pathname.endsWith('/diagram')) {
        await route.fulfill({
          json: { xml: XML, activeActivityIds: ['approveTask'], deadLetterActivityIds: [] },
        })
      } else if (pathname.endsWith('/variables')) {
        await route.fulfill({
          json: { processVariables: [], executionScopes: [], source: 'runtime' },
        })
      } else if (pathname === '/api/instances/eng1/inst-1') {
        await route.fulfill({ json: VITALS })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({
          json: [{ id: 'job-1', state: 'COMPLETED', verb: 'retry-job', items: [] }],
        })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

async function open(page: Page, width: number, height: number): Promise<void> {
  await page.setViewportSize({ width, height })
  await mockBff(page)
  await page.goto('/inspect/eng1/inst-1')
  await expect(page.locator('.diagram-canvas').first()).toBeVisible()
  await expect(page.getByRole('tablist').first()).toBeVisible()
}

for (const size of [
  { width: 1440, height: 900 },
  { width: 1024, height: 700 },
]) {
  const tag = `${String(size.width)}x${String(size.height)}`

  test(`${tag}: the collapsed ops drawer does not cover the tablist in the first view`, async ({
    page,
  }) => {
    await open(page, size.width, size.height)
    const tabs = await page.getByRole('tablist').first().boundingBox()
    const drawer = await page.locator('.ops-drawer').first().boundingBox()
    expect(tabs).not.toBeNull()
    expect(drawer).not.toBeNull()
    if (!tabs || !drawer) return
    const apart =
      tabs.y + tabs.height <= drawer.y + 1 ||
      tabs.x + tabs.width <= drawer.x ||
      drawer.x + drawer.width <= tabs.x
    expect(apart).toBe(true)
  })

  test(`${tag}: diagram toolbar and legend are inset from the viewport edge`, async ({ page }) => {
    await open(page, size.width, size.height)
    const bar = await page.getByRole('toolbar', { name: 'diagram view' }).boundingBox()
    const legend = await page.getByRole('list', { name: 'diagram legend' }).boundingBox()
    expect(bar?.x).toBeGreaterThanOrEqual(12)
    expect(legend?.x).toBeGreaterThanOrEqual(12)
  })

  test(`${tag}: the engine strip is one line on the instance page`, async ({ page }) => {
    await open(page, size.width, size.height)
    const strip = await page.getByRole('group', { name: 'Engine health' }).boundingBox()
    expect(strip?.height).toBeLessThanOrEqual(40)
  })
}

test('instance page with the compact engine strip passes axe', async ({ page }) => {
  await open(page, 1440, 900)
  await scanA11y(page, 'instance detail polish')
})
