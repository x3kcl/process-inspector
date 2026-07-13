// v1.1 flow-surgery smokes: the change-state form → simulation modal → cancel arc, and
// the restart-as-new gating + fork. Hermetic — every /api call is fulfilled by the route
// handler below; the one invariant these tests exist to protect is simulation-first:
// no execute route is EVER hit, and the execute button only exists after a preview.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = {
  id: 'eng1',
  name: 'Orders DEV',
  environment: 'dev',
  mode: 'FULL',
  reachable: true,
  capabilities: { changeState: true, migration: false },
}

const DIAGRAM_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
             targetNamespace="smoke">
  <process id="orderFlow" name="Order flow" isExecutable="true">
    <startEvent id="theStart" name="Order received"/>
    <userTask id="approveTask" name="Approve order"/>
    <serviceTask id="chargeTask" name="Charge card"/>
    <endEvent id="theEnd" name="Done"/>
  </process>
  <bpmndi:BPMNDiagram id="di">
    <bpmndi:BPMNPlane id="plane" bpmnElement="orderFlow">
      <bpmndi:BPMNShape id="s1" bpmnElement="theStart"><omgdc:Bounds x="0" y="0" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s2" bpmnElement="approveTask"><omgdc:Bounds x="80" y="0" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s3" bpmnElement="chargeTask"><omgdc:Bounds x="220" y="0" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s4" bpmnElement="theEnd"><omgdc:Bounds x="360" y="0" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`

const RUNNING_VITALS = {
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

const ENDED_VITALS = {
  ...RUNNING_VITALS,
  status: 'COMPLETED',
  flags: { ended: true, suspended: false },
  endTime: '2026-07-02T09:00:00Z',
  currentActivities: [],
}

const PREVIEW = {
  engineId: 'eng1',
  processInstanceId: 'inst-1',
  processDefinitionId: 'orderFlow:3:abc',
  method: 'POST',
  enginePath: '/runtime/process-instances/inst-1/change-state',
  payload: { cancelActivityIds: ['approveTask'], startActivityIds: ['chargeTask'] },
  summary:
    'Cancel the token at “Approve order” and start a fresh token at “Charge card” on order-4711 (Orders DEV).',
  warnings: [
    {
      code: 'parallel-sibling',
      message:
        'approveTask sits inside a parallel gateway — sibling branches keep running and the join may now wait forever.',
    },
  ],
  simulationNote:
    'This plan was calculated by the inspector from the deployed model — it is NOT an engine-verified dry-run.',
}

// Fulfills every /api call; records mutating calls so tests can assert none fired.
// Predicate (not a glob): '**/api/**' would also hijack Vite's /src/api/* modules.
async function mockBff(page: Page, vitals: object, executed: string[]): Promise<void> {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && /\/(change-state\/execute|restart)$/.exec(pathname) !== null) {
        executed.push(pathname)
        await route.fulfill({ status: 500, json: { title: 'must never be called' } })
        return
      }
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'OPERATOR', engineRoles: { eng1: 'OPERATOR' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname.endsWith('/change-state/preview')) {
        await route.fulfill({ json: PREVIEW })
      } else if (pathname.endsWith('/diagram')) {
        await route.fulfill({
          json: { xml: DIAGRAM_XML, activeActivityIds: ['approveTask'], deadLetterActivityIds: [] },
        })
      } else if (pathname.endsWith('/variables')) {
        await route.fulfill({
          json: { processVariables: [], executionScopes: [], source: 'runtime' },
        })
      } else if (pathname === '/api/instances/eng1/inst-1') {
        await route.fulfill({ json: vitals })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
}

test('change-state is simulation-first: form, preview modal, cancel — execute never fires', async ({
  page,
}) => {
  const executed: string[] = []
  await mockBff(page, RUNNING_VITALS, executed)
  await page.goto('/inspect/eng1/inst-1')

  // Entry point: enabled on a running instance for an OPERATOR.
  const open = page.getByRole('button', { name: 'Change state / move token' })
  await expect(open).toBeEnabled()
  await open.click()

  // Step 1 (intent): pick source + target. There is NO execute affordance yet.
  const form = page.getByRole('dialog', { name: /Change state \/ move token/ })
  await expect(form).toBeVisible()
  await scanA11y(page, 'change-state form open, no execute affordance yet')
  await expect(page.getByRole('button', { name: /Execute move/ })).toHaveCount(0)
  await form
    .getByRole('group', { name: 'source nodes' })
    .getByRole('checkbox', { name: 'Approve order (approveTask) · userTask' })
    .check()
  await form
    .getByRole('group', { name: 'target nodes' })
    .getByRole('checkbox', { name: 'Charge card (chargeTask) · serviceTask' })
    .check()
  await form.getByRole('button', { name: 'Simulate move (1 → 1)' }).click()

  // Step 2 (verify): the modal renders the BFF simulation verbatim.
  const verify = page.getByRole('dialog', { name: 'Verify the move — simulation' })
  await expect(verify).toBeVisible()
  await expect(verify.getByText('Cancel the token at “Approve order”')).toBeVisible()
  await expect(verify.getByRole('alert').filter({ hasText: 'parallel-sibling' })).toBeVisible()
  await expect(verify.getByText('NOT an engine-verified dry-run')).toBeVisible()
  await scanA11y(page, 'simulation verify modal open with warnings')
  await verify.getByText('exact engine request').click()
  await expect(verify.getByText('"cancelActivityIds"')).toBeVisible()
  await expect(verify.getByText('"startActivityIds"')).toBeVisible()

  // Guard ladder: execute unlocks only after a ≥10-char reason (dev engine — no token).
  const execute = verify.getByRole('button', { name: 'Execute move on order-4711' })
  await expect(execute).toBeDisabled()
  await verify.getByRole('textbox').fill('INC-1234: token stuck after payment fix')
  await expect(execute).toBeEnabled()

  // Cancel out — the whole arc must leave the engine untouched.
  await verify.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(verify).toHaveCount(0)
  expect(executed).toEqual([])
})

test('issue #102: diagram-click picker adds a clicked node to sources/targets, same as the checklist', async ({
  page,
}) => {
  const executed: string[] = []
  await mockBff(page, RUNNING_VITALS, executed)
  await page.goto('/inspect/eng1/inst-1')

  await page.getByRole('button', { name: 'Change state / move token' }).click()
  const form = page.getByRole('dialog', { name: /Change state \/ move token/ })
  await expect(form).toBeVisible()

  // Off by default — a plain click does nothing to either list.
  const canvas = form.locator('.change-state-diagram .diagram-canvas')
  await expect(canvas).toBeVisible()
  await scanA11y(page, 'change-state form with the diagram picker mounted, mode off')

  // Source mode: clicking the one active node (approveTask) checks it in the checklist —
  // same state, two entry points, proving the diagram is a supplementary picker, not a
  // parallel source of truth. bpmn-js tags every element's graphics group with
  // data-element-id (diagram-js's own ElementRegistry constant) — a stable click target,
  // unlike the text label, whose invisible djs-hit overlay intercepts pointer events.
  await form.getByRole('button', { name: 'Click adds sources' }).click()
  await canvas.locator('[data-element-id="approveTask"]').click()
  await expect(
    form
      .getByRole('group', { name: 'source nodes' })
      .getByRole('checkbox', { name: /Approve order/ }),
  ).toBeChecked()

  // Target mode: clicking an eligible catalog node (chargeTask) checks it too.
  await form.getByRole('button', { name: 'Click adds targets' }).click()
  await canvas.locator('[data-element-id="chargeTask"]').click()
  await expect(
    form
      .getByRole('group', { name: 'target nodes' })
      .getByRole('checkbox', { name: /Charge card/ }),
  ).toBeChecked()

  // Clicking an ineligible node in source mode (the end event holds no token) surfaces
  // the inline hint rather than silently no-opping or crashing.
  await form.getByRole('button', { name: 'Click adds sources' }).click()
  await canvas.locator('[data-element-id="theEnd"]').click()
  await expect(form.getByRole('alert').filter({ hasText: 'no active token' })).toBeVisible()

  await form.getByRole('button', { name: 'Simulate move (1 → 1)' }).click()
  await expect(page.getByRole('dialog', { name: 'Verify the move — simulation' })).toBeVisible()
  expect(executed).toEqual([])
})

test('issue #102: rerun from activity — edit a variable, then guided straight into the move step', async ({
  page,
}) => {
  const executed: string[] = []
  const dispatched: string[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      const method = route.request().method()
      if (method === 'POST' && /\/(change-state\/execute|restart)$/.exec(pathname) !== null) {
        executed.push(pathname)
        await route.fulfill({ status: 500, json: { title: 'must never be called' } })
        return
      }
      if (method === 'POST' && pathname.endsWith('/actions/edit-variable')) {
        dispatched.push(pathname)
        await route.fulfill({
          json: { outcome: 'applied', deltaStatement: 'amount changed to 500' },
        })
        return
      }
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'OPERATOR', engineRoles: { eng1: 'OPERATOR' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname.endsWith('/diagram')) {
        await route.fulfill({
          json: { xml: DIAGRAM_XML, activeActivityIds: ['approveTask'], deadLetterActivityIds: [] },
        })
      } else if (pathname.endsWith('/change-state/preview')) {
        await route.fulfill({ json: PREVIEW })
      } else if (pathname.endsWith('/variables/amount')) {
        await route.fulfill({ json: { name: 'amount', type: 'long', value: 100 } })
      } else if (pathname.endsWith('/variables')) {
        await route.fulfill({
          json: {
            processVariables: [{ name: 'amount', type: 'long', value: 100 }],
            executionScopes: [],
            source: 'runtime',
          },
        })
      } else if (pathname === '/api/instances/eng1/inst-1') {
        await route.fulfill({ json: RUNNING_VITALS })
      } else if (pathname === '/api/bulk') {
        await route.fulfill({ json: [] })
      } else {
        await route.fulfill({ json: {} })
      }
    },
  )
  await page.goto('/inspect/eng1/inst-1')

  await page.getByRole('button', { name: 'Rerun from activity' }).click()
  const edit = page.getByRole('dialog', { name: /Rerun from activity/ })
  await expect(edit).toBeVisible()
  await scanA11y(page, 'rerun-from-activity edit step open')

  await edit.getByRole('combobox', { name: 'variable name' }).selectOption('amount')
  await expect(edit.getByText('(no value / null)')).toHaveCount(0)
  const review = edit.getByRole('button', { name: 'Review the edit…' })
  await expect(review).toBeDisabled()
  await edit.getByRole('textbox').fill('500')
  await expect(review).toBeEnabled()
  await review.click()

  // Step 2: the EXISTING VerifyModal, unchanged — same component the standalone
  // variable editor uses.
  const verify = page.getByRole('dialog', { name: 'Verify the change' })
  await expect(verify).toBeVisible()
  await scanA11y(page, 'rerun-from-activity verify step (shared VerifyModal)')
  await verify.getByRole('textbox').first().fill('INC-1234: bad amount before rerouting')
  await verify.getByRole('button', { name: /Change amount/ }).click()

  // Step 3: guided straight into the move step — the EXISTING ChangeStateModal,
  // unchanged, including its own diagram-click picker.
  const move = page.getByRole('dialog', { name: /Change state \/ move token/ })
  await expect(move).toBeVisible()
  await scanA11y(page, 'rerun-from-activity guided into the move step')
  expect(dispatched).toEqual(['/api/instances/eng1/inst-1/actions/edit-variable'])
  expect(executed).toEqual([])
})

test('restart-as-new is greyed on a running instance, with the gate named', async ({ page }) => {
  const executed: string[] = []
  await mockBff(page, RUNNING_VITALS, executed)
  await page.goto('/inspect/eng1/inst-1')

  const restart = page.getByRole('button', { name: 'Restart as new instance' })
  await expect(restart).toBeDisabled()
  await expect(restart).toHaveAttribute('title', /ended/)
  await scanA11y(page, 'restart button disabled on a running instance')
  expect(executed).toEqual([])
})

test('restart-as-new on an ended instance forces the version fork before unlocking', async ({
  page,
}) => {
  const executed: string[] = []
  await mockBff(page, ENDED_VITALS, executed)
  await page.goto('/inspect/eng1/inst-1')

  // Change-state has no runtime state to act on; restart is the live verb now.
  await expect(page.getByRole('button', { name: 'Change state / move token' })).toBeDisabled()
  await page.getByRole('button', { name: 'Restart as new instance' }).click()

  const modal = page.getByRole('dialog', { name: /Restart as new instance/ })
  await expect(modal).toBeVisible()
  await scanA11y(page, 'restart modal open, version fork pending')
  const confirm = modal.getByRole('button', { name: /Restart order-4711 as a new instance/ })

  // The fork is mandatory and un-defaulted: reason alone must not unlock.
  await modal.getByRole('textbox').fill('INC-1234: resurrect after data fix')
  await expect(confirm).toBeDisabled()
  await modal.getByRole('button', { name: 'Pin to original version (start on v3)' }).click()
  await expect(confirm).toBeEnabled()
  await expect(confirm).toHaveText(/on v3/)
  await modal.getByRole('button', { name: 'Use latest deployed version' }).click()
  await expect(confirm).toHaveText(/on the latest version/)

  // Cancel out — nothing dispatched.
  await modal.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(modal).toHaveCount(0)
  expect(executed).toEqual([])
})
