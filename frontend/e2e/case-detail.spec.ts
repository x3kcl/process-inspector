// Case Inspector Phase 2: the polymorphic CMMN case-detail page (cmmn-js diagram + plan-item
// state-machine timeline), and the drill from the Phase-1 scope drawer into it. Proven against
// the real rendered UI with a mocked BFF.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

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
    jobs: [
      {
        id: 'job-1',
        elementName: 'Failing service',
        exceptionMessage: 'Unknown property used in expression: ${nonExistentBean.doStuff()}',
        retries: 0,
      },
    ],
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
  /** Signed-in role (default RESPONDER); ADMIN unlocks the tier-3 delete. */
  role?: string
}

async function mockBff(page: Page, diagram: DiagramOpts): Promise<void> {
  const role = diagram.role ?? 'RESPONDER'
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role, engineRoles: { eng1: role } } })
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
      } else if (pathname === '/api/cases/eng1/case-1/actions/retry-job') {
        // Phase 3: the case-scoped retry route. Assert the request shape then answer ok.
        await route.fulfill({
          json: {
            outcome: 'ok',
            httpStatus: 200,
            deltaStatement:
              'Job job-1 moved back to the executable queue; engine-default retries restored.',
            auditId: 'audit-1',
          },
        })
      } else if (pathname === '/api/cases/eng1/case-1/actions/delete-deadletter') {
        // Phase 3: the tier-3 case-scoped delete. Answer with the scope-honest CMMN delta.
        await route.fulfill({
          json: {
            outcome: 'ok',
            httpStatus: 200,
            deltaStatement:
              'Dead-letter job job-1 deleted. The plan item is orphaned — the case cannot continue' +
              ' past this step on its own, and this tool offers no CMMN rescue verb (no change-state' +
              ' for cases).',
            auditId: 'audit-2',
          },
        })
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
  // Phase 3: the read-only badge is retired; the why-stuck panel now offers a retry per job.
  await expect(page.getByText('read-only')).toHaveCount(0)
  await expect(
    page.locator('.case-why-stuck').getByRole('button', { name: 'Retry job' }),
  ).toBeVisible()
  await scanA11y(page, 'case vitals + why-stuck panel with retry button')

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
  await scanA11y(page, 'plan-item timeline tree rendered')
})

test('retrying a CMMN dead-letter job POSTs the case-scoped action and reports the delta', async ({
  page,
}) => {
  await mockBff(page, { graphicalNotationDefined: false })
  const retry = page.waitForRequest(
    (req) =>
      req.method() === 'POST' && req.url().includes('/api/cases/eng1/case-1/actions/retry-job'),
  )
  await page.goto('/case/eng1/case-1')

  // A RESPONDER on a DEV engine: single-click retry (no prod two-step), gated on scopeType.
  await page.locator('.case-why-stuck').getByRole('button', { name: 'Retry job' }).click()

  // The request carries the dead-letter job id in the body — the BFF reads the CMMN DLQ by-id.
  const request = await retry
  expect(request.postDataJSON()).toMatchObject({ jobId: 'job-1' })

  // The server's delta statement surfaces as the success toast (never a bare "success").
  await expect(page.getByText(/moved back to the executable queue/)).toBeVisible()
  await scanA11y(page, 'retry success toast shown')
})

test('a RESPONDER cannot delete a CMMN dead-letter job — the tier-3 button is gated', async ({
  page,
}) => {
  await mockBff(page, { graphicalNotationDefined: false }) // default RESPONDER
  await page.goto('/case/eng1/case-1')

  // Retry (tier 0) is offered; Delete (tier 3 / ADMIN) is present but disabled — greyed, never hidden.
  await expect(
    page.locator('.case-why-stuck').getByRole('button', { name: 'Retry job' }),
  ).toBeEnabled()
  await expect(
    page.locator('.case-why-stuck').getByRole('button', { name: 'Delete', exact: true }),
  ).toBeDisabled()
  await scanA11y(page, 'delete button disabled for RESPONDER')
})

test('an ADMIN deletes a CMMN dead-letter job through the typed-confirm modal', async ({
  page,
}) => {
  await mockBff(page, { graphicalNotationDefined: false, role: 'ADMIN' })
  const del = page.waitForRequest(
    (req) =>
      req.method() === 'POST' &&
      req.url().includes('/api/cases/eng1/case-1/actions/delete-deadletter'),
  )
  await page.goto('/case/eng1/case-1')

  // Open the destructive modal (never an inline click for a tier-3 verb).
  await page.locator('.case-why-stuck').getByRole('button', { name: 'Delete', exact: true }).click()
  const modal = page.getByRole('dialog')
  await expect(modal).toBeVisible()
  // Scope-honest blast radius: no BPMN change-state rescue for a case.
  await expect(modal).toContainText('no change-state rescue')
  await scanA11y(page, 'delete confirm modal open for ADMIN')

  // The confirm is disabled until a ≥10-char reason lands (DEV engine → no typed token gate).
  const confirm = modal.getByRole('button', { name: /Delete dead-letter job/ })
  await expect(confirm).toBeDisabled()
  await modal.getByRole('textbox').first().fill('case abandoned; job orphaned deliberately')
  await expect(confirm).toBeEnabled()
  await confirm.click()

  // The request carries the job id AND the reason (tier-3 reason discipline reaches the wire).
  const request = await del
  expect(request.postDataJSON()).toMatchObject({
    jobId: 'job-1',
    reason: 'case abandoned; job orphaned deliberately',
  })

  // The scope-honest server delta surfaces as the toast; the modal closes.
  await expect(page.getByText(/no CMMN rescue verb/)).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await scanA11y(page, 'delete success toast, modal closed')
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
  await scanA11y(page, 'cmmn-js canvas rendered with failed marker')
})
