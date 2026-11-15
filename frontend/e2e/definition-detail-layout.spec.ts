// #676 (study F8/F9): definition-page header sits inside the app's page padding, and the
// change-state modal never exceeds the viewport (title visible, Simulate reachable).
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const ENGINE = {
  id: 'eng1',
  name: 'Orders DEV',
  environment: 'dev',
  mode: 'FULL',
  reachable: true,
  capabilities: { changeState: true, migration: false },
}

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
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname.endsWith('/diagram')) {
        await route.fulfill({
          json: { xml: XML, activeActivityIds: ['approveTask'], deadLetterActivityIds: [] },
        })
      } else if (pathname.endsWith('/xml')) {
        await route.fulfill({ json: { xml: XML } })
      } else if (pathname === '/api/definitions/eng1/orderFlow:3:abc') {
        await route.fulfill({
          json: { key: 'orderFlow', version: 3, kind: 'BPMN', suspended: false, versions: [] },
        })
      } else if (pathname.endsWith('/variables')) {
        await route.fulfill({
          json: { processVariables: [], executionScopes: [], source: 'runtime' },
        })
      } else if (pathname === '/api/instances/eng1/inst-1') {
        await route.fulfill({ json: VITALS })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

for (const size of [
  { width: 1440, height: 900 },
  { width: 1024, height: 700 },
]) {
  test(`definition header is inset from the viewport edge at ${String(size.width)}x${String(size.height)}`, async ({
    page,
  }) => {
    await page.setViewportSize(size)
    await mockBff(page)
    await page.goto('/definitions/eng1/orderFlow%3A3%3Aabc')
    const heading = page.locator('main h1')
    await expect(heading).toBeVisible()
    // Shared page-header pattern: the Breadcrumbs trail, not a bare default-styled link.
    const crumb = page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', {
      name: 'Definitions',
    })
    await expect(crumb).toBeVisible()
    const h = await heading.boundingBox()
    const c = await crumb.boundingBox()
    expect(h?.x).toBeGreaterThanOrEqual(12)
    expect(c?.x).toBeGreaterThanOrEqual(12)
  })

  test(`change-state modal fits the viewport at ${String(size.width)}x${String(size.height)}`, async ({
    page,
  }) => {
    await page.setViewportSize(size)
    await mockBff(page)
    await page.goto('/inspect/eng1/inst-1')
    await page.getByRole('button', { name: 'Change state / move token' }).click()
    const dialog = page.getByRole('dialog', { name: /Change state \/ move token/ })
    await expect(dialog).toBeVisible()
    const title = dialog.getByRole('heading')
    const box = await title.boundingBox()
    expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(size.height)
    const card = await dialog.boundingBox()
    expect(card?.y).toBeGreaterThanOrEqual(0)
    expect(box?.y).toBeGreaterThanOrEqual(card?.y ?? 0)
    expect((card?.y ?? 0) + (card?.height ?? 0)).toBeLessThanOrEqual(size.height)
    const simulate = dialog.getByRole('button', { name: /Simulate move/ })
    await expect(simulate).toBeVisible()
    const s = await simulate.boundingBox()
    expect((s?.y ?? 0) + (s?.height ?? 0)).toBeLessThanOrEqual(size.height)
  })
}

test('definition detail header and tabs pass the axe scan', async ({ page }) => {
  const { scanA11y } = await import('./a11y')
  await mockBff(page)
  await page.goto('/definitions/eng1/orderFlow%3A3%3Aabc')
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toBeVisible()
  await scanA11y(page, 'definition detail header')
})
