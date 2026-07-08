// Case Inspector Phase 2: the polymorphic CMMN case-detail page (cmmn-js diagram + plan-item
// state-machine timeline), and the drill from the Phase-1 scope drawer into it. Proven against
// the real rendered UI with a mocked BFF.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const ENGINE = {
  id: 'eng1',
  name: 'Payments DEV',
  environment: 'dev',
  reachable: true,
  capabilities: {
    changeState: true,
    migration: true,
    externalWorkerJobs: true,
    scopeType: true,
    activityHistory: true,
  },
}

const VITALS = {
  engineId: 'eng1',
  caseInstanceId: 'case-1',
  businessKey: 'order-42',
  state: 'ACTIVE',
  caseDefinitionId: 'def-uuid',
  caseDefinitionKey: 'demoFailingCase',
  caseDefinitionName: 'Demo failing case',
  caseDefinitionVersion: 1,
  startTime: '2026-07-08T08:00:00.000+00:00',
  present: true,
  ended: false,
  failing: {
    deadLetterJobCount: 1,
    firstException: 'Unknown property used in expression: ${nonExistentBean.doStuff()}',
    failingElementName: 'Failing service',
  },
}

// Two runtime plan items: a stage and a servicetask nested inside it; the servicetask carries the
// FAILED live-job badge (joined by planItemInstanceId, spike Q7).
const PLAN_ITEMS = {
  available: true,
  truncated: false,
  planItems: [
    {
      id: 'pi-stage',
      elementId: 'planItem_stage',
      name: 'Fulfilment',
      planItemDefinitionType: 'stage',
      state: 'active',
      stage: true,
    },
    {
      id: 'pi-svc',
      elementId: 'planItem_svc',
      name: 'Failing service',
      planItemDefinitionType: 'servicetask',
      state: 'async-active',
      stage: false,
      stageInstanceId: 'pi-stage',
      lastStartedTime: '2026-07-08T08:00:01.000+00:00',
      liveJobState: 'FAILED',
    },
  ],
}

// A minimal CMMN 1.1 model WITH CMMNDI so cmmn-js actually draws (a DI-less model renders the
// honest no-layout state instead — the other test).
const CMMN_WITH_DI = `<?xml version="1.0" encoding="UTF-8"?>
<cmmn:definitions xmlns:cmmn="http://www.omg.org/spec/CMMN/20151109/MODEL"
  xmlns:cmmndi="http://www.omg.org/spec/CMMN/20151109/CMMNDI"
  xmlns:dc="http://www.omg.org/spec/CMMN/20151109/DC"
  xmlns:di="http://www.omg.org/spec/CMMN/20151109/DI"
  targetNamespace="http://flowable.org/cmmn">
  <cmmn:case id="demoFailingCase">
    <cmmn:casePlanModel id="casePlanModel" name="Demo failing case">
      <cmmn:planItem id="planItem_svc" name="Failing service" definitionRef="failingService" />
      <cmmn:task id="failingService" name="Failing service" />
    </cmmn:casePlanModel>
  </cmmn:case>
  <cmmndi:CMMNDI>
    <cmmndi:CMMNDiagram id="CMMNDiagram_demo">
      <cmmndi:CMMNShape id="Shape_casePlanModel" cmmnElementRef="casePlanModel">
        <dc:Bounds height="240" width="420" x="40" y="40" />
      </cmmndi:CMMNShape>
      <cmmndi:CMMNShape id="Shape_planItem_svc" cmmnElementRef="planItem_svc">
        <dc:Bounds height="60" width="120" x="120" y="120" />
      </cmmndi:CMMNShape>
    </cmmndi:CMMNDiagram>
  </cmmndi:CMMNDI>
</cmmn:definitions>`

interface DiagramOpts {
  graphicalNotationDefined: boolean
}

async function mockBff(page: Page, diagram: DiagramOpts): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'RESPONDER', engineRoles: { eng1: 'RESPONDER' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname === '/api/cases/eng1/case-1') {
        await route.fulfill({ json: VITALS })
      } else if (pathname === '/api/cases/eng1/case-1/diagram') {
        await route.fulfill({
          json: {
            xml: diagram.graphicalNotationDefined ? CMMN_WITH_DI : '<cmmn:definitions />',
            graphicalNotationDefined: diagram.graphicalNotationDefined,
            activePlanItemElementIds: ['planItem_svc'],
            failedPlanItemElementIds: ['planItem_svc'],
          },
        })
      } else if (pathname === '/api/cases/eng1/case-1/plan-items') {
        await route.fulfill({ json: PLAN_ITEMS })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

test('the case detail renders vitals, the plan-item timeline, and the honest no-layout state', async ({
  page,
}) => {
  await mockBff(page, { graphicalNotationDefined: false })
  await page.goto('/case/eng1/case-1')

  // Vitals: the resolved case type + ACTIVE state (never SUSPENDED) + the why-stuck strip.
  await expect(page.getByRole('heading', { name: /Demo failing case/ })).toBeVisible()
  await expect(page.getByText('ACTIVE', { exact: true })).toBeVisible()
  await expect(page.locator('.case-why-stuck')).toContainText('Failing service')
  await expect(page.locator('.case-why-stuck')).toContainText('nonExistentBean')
  await expect(page.getByText('read-only')).toBeVisible()

  // A DI-less model renders the honest no-layout state, never a blank canvas.
  await expect(page.getByText('No case diagram')).toBeVisible()

  // The plan-item timeline nests the servicetask under its stage and badges it FAILED (non-hue).
  const tree = page.getByRole('tree', { name: 'CMMN plan items' })
  const svc = tree.getByRole('treeitem').filter({ hasText: 'Failing service' })
  await expect(svc).toContainText('Failed')
  await expect(svc).toHaveAttribute('aria-level', '2') // nested one level under the stage
  await expect(tree.getByRole('treeitem').filter({ hasText: 'Fulfilment' })).toHaveAttribute(
    'aria-level',
    '1',
  )
})

test('a case with graphical notation renders the cmmn-js canvas with the failed marker', async ({
  page,
}) => {
  await mockBff(page, { graphicalNotationDefined: true })
  await page.goto('/case/eng1/case-1')

  // cmmn-js draws the plan item as an SVG element keyed by its cmmnElementRef, and the FAILED
  // marker class lands on it. The bpmn.io watermark the viewer injects stays present (R-GOV-05):
  // cmmn-js 0.20 emits the SAME `.bjs-powered-by` element as bpmn-js (not a `cmmn-*` variant).
  const planItem = page.locator('.case-diagram [data-element-id="planItem_svc"]')
  await expect(planItem).toBeVisible()
  await expect(planItem).toHaveClass(/marker-deadletter/)
  await expect(page.locator('.case-diagram .bjs-powered-by')).toBeVisible()
  await expect(page.getByText('No case diagram')).toHaveCount(0)
})
