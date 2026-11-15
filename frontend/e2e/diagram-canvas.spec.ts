// #673 diagram canvas ergonomics (SPEC §4 Stage 2): centred first fit, Fit / zoom toolbar,
// scroll-to-selection only when needed, a legend of the markers actually present, and a
// markers-only change that repaints the live canvas instead of re-importing it. Hermetic:
// every /api call is fulfilled here.
import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { scanA11y } from './a11y'

const ENGINE = { id: 'eng1', name: 'Payments DEV', environment: 'dev', reachable: true }
const BASE = '/api/instances/eng1/inst-failed'

const VITALS = {
  compositeId: 'eng1:inst-failed',
  engineId: 'eng1',
  processInstanceId: 'inst-failed',
  definitionKey: 'payment',
  definitionVersion: 3,
  processDefinitionId: 'payment:3:abc',
  startTime: '2026-07-07T07:00:00Z',
  status: 'FAILED',
  flags: { ended: false, suspended: false, hasDeadLetterJobs: true, hasFailingJobs: false },
  whyStuck: {
    exceptionFirstLine: 'java.lang.ArithmeticException: / by zero',
    failingActivityId: 'charge',
    failureTime: '2026-07-07T07:05:00Z',
    deadLetterJobs: 1,
    retryingJobs: 0,
  },
}

// Wide and tall: the canvas fit is height-bound, so an uncentred fit hugs the left edge.
const shape = (id: string, x: number, y: number, w = 100, h = 60) =>
  `<bpmndi:BPMNShape id="di-${id}" bpmnElement="${id}"><omgdc:Bounds x="${String(x)}" y="${String(y)}" width="${String(w)}" height="${String(h)}"/></bpmndi:BPMNShape>`
const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
  targetNamespace="http://example.com/test">
  <process id="payment" isExecutable="true">
    <startEvent id="theStart" name="Start"/>
    <serviceTask id="task1" name="Validate"/>
    <serviceTask id="task2" name="Reserve"/>
    <serviceTask id="charge" name="Charge"/>
    <serviceTask id="refund" name="Refund"/>
    <serviceTask id="lowTask" name="Notify"/>
    <endEvent id="theEnd" name="Done"/>
  </process>
  <bpmndi:BPMNDiagram id="di">
    <bpmndi:BPMNPlane id="plane" bpmnElement="payment">
      ${shape('theStart', 0, 12, 36, 36)}
      ${shape('task1', 100, 0)}
      ${shape('task2', 300, 0)}
      ${shape('charge', 500, 0)}
      ${shape('refund', 700, 0)}
      ${shape('lowTask', 300, 400)}
      ${shape('theEnd', 900, 12, 36, 36)}
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`

const DEAD_LETTER_JOB = {
  id: 'dl-1',
  elementId: 'charge',
  elementName: 'Charge',
  exceptionMessage: 'java.lang.ArithmeticException: / by zero',
  retries: 0,
  processInstanceId: 'inst-failed',
}

const DIFF = {
  subject: { processInstanceId: 'inst-failed', startTime: '2026-07-07T07:00:00Z', ended: false },
  sibling: { processInstanceId: 'good-1', endTime: '2026-07-07T06:00:00Z', ended: true },
  sameDefinition: true,
  previewCappedPresent: false,
  variables: [],
  path: {
    subjectPath: [],
    siblingPath: [],
    onlyInSubject: ['charge'],
    onlyInSibling: ['refund'],
    common: ['theStart'],
  },
  timings: [],
}

async function mockBff(page: Page, markers: { active: string[]; deadLetter: string[] }) {
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
            activeActivityIds: markers.active,
            deadLetterActivityIds: markers.deadLetter,
          },
        })
      else if (pathname === `${BASE}/jobs`)
        await route.fulfill({
          json: { executable: [], timer: [], suspended: [], deadLetter: [DEAD_LETTER_JOB] },
        })
      else if (pathname === `${BASE}/nearest-sibling`)
        await route.fulfill({
          json: {
            found: true,
            sibling: { processInstanceId: 'good-1', endTime: '2026-07-07T06:00:00Z' },
            candidatesScanned: 3,
            definitionId: 'payment:3:abc',
            processDefinitionKey: 'payment',
            definitionVersion: 3,
          },
        })
      else if (pathname === `${BASE}/diff/good-1`) await route.fulfill({ json: DIFF })
      else if (pathname === '/api/bulk') await route.fulfill({ json: [] })
      else await route.fulfill({ json: {} })
    },
  )
}

const canvasOf = (page: Page) => page.locator('.diagram-canvas')
const visualOf = (page: Page, id: string) =>
  page.locator(`.diagram-canvas [data-element-id="${id}"] .djs-visual`)
const viewbox = (page: Page) =>
  page.locator('.diagram-canvas .viewport').first().getAttribute('transform')
const scaleOf = (transform: string | null) => Number(/matrix\(([^,\s]+)/.exec(transform ?? '')?.[1])

/** The viewbox once the page layout has stopped resizing (and re-fitting) the canvas. */
async function settledViewbox(page: Page) {
  let last = await viewbox(page)
  await expect
    .poll(
      async () => {
        const now = await viewbox(page)
        const same = now === last
        last = now
        return same
      },
      { intervals: [250] },
    )
    .toBe(true)
  return last
}

async function box(locator: Locator) {
  const b = await locator.boundingBox()
  if (b === null) throw new Error('element has no box')
  return b
}

async function expectInside(inner: Locator, outer: Locator) {
  const i = await box(inner)
  const o = await box(outer)
  expect(i.x).toBeGreaterThanOrEqual(o.x + 2)
  expect(i.y).toBeGreaterThanOrEqual(o.y)
  expect(i.x + i.width).toBeLessThanOrEqual(o.x + o.width)
  expect(i.y + i.height).toBeLessThanOrEqual(o.y + o.height)
}

/** Drag the empty canvas margin sideways — NavigatedViewer's drag-to-pan. */
async function pan(page: Page, dx: number) {
  const c = await box(canvasOf(page))
  await page.mouse.move(c.x + 10, c.y + c.height / 2)
  await page.mouse.down()
  await page.mouse.move(c.x + 10 + dx / 2, c.y + c.height / 2)
  await page.mouse.move(c.x + 10 + dx, c.y + c.height / 2)
  await page.mouse.up()
}

test('first fit is centred and the toolbar fits, zooms in and out', async ({ page }) => {
  await mockBff(page, { active: ['task1'], deadLetter: ['charge'] })
  await page.goto('/inspect/eng1/inst-failed')
  await expect(visualOf(page, 'theStart')).toBeVisible()

  const fitted = await settledViewbox(page)
  await expectInside(visualOf(page, 'theStart'), canvasOf(page))
  await scanA11y(page, 'diagram with toolbar and legend')

  await page.getByRole('button', { name: 'Zoom in' }).click()
  const zoomedIn = scaleOf(await viewbox(page))
  expect(zoomedIn).toBeGreaterThan(scaleOf(fitted))
  await page.getByRole('button', { name: 'Zoom out' }).click()
  await page.getByRole('button', { name: 'Zoom out' }).click()
  expect(scaleOf(await viewbox(page))).toBeLessThan(scaleOf(fitted))

  await pan(page, 150)
  expect(await viewbox(page)).not.toBe(fitted)
  await page.getByRole('button', { name: 'Fit', exact: true }).click()
  expect(await viewbox(page)).toBe(fitted)

  // Keyboard: the toolbar is in the tab order and operable with Enter.
  await page.getByRole('button', { name: 'Fit', exact: true }).focus()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: 'Zoom in' })).toBeFocused()
  await page.keyboard.press('Enter')
  expect(scaleOf(await viewbox(page))).toBeGreaterThan(scaleOf(fitted))
})

test('selection scrolls only when the activity is out of view', async ({ page }) => {
  await mockBff(page, { active: ['task1'], deadLetter: ['charge'] })
  await page.goto('/inspect/eng1/inst-failed?tab=errors-jobs')
  await expect(visualOf(page, 'lowTask')).toBeVisible()

  // A fully visible activity near the canvas edge: clicking it must not move the model.
  const before = await settledViewbox(page)
  await page.locator('.diagram-canvas [data-element-id="lowTask"] .djs-hit').click()
  await expect(page.locator('.diagram-canvas g.marker-selected')).toHaveCount(1)
  expect(await viewbox(page)).toBe(before)

  // Pan "Charge" out of view, then "show on diagram" brings it back.
  await pan(page, 900)
  const panned = await box(visualOf(page, 'charge'))
  const c = await box(canvasOf(page))
  expect(panned.x).toBeGreaterThan(c.x + c.width)
  await page
    .locator('details.lane-deadLetter')
    .getByRole('button', { name: 'show on diagram' })
    .click()
  await expect
    .poll(async () => {
      const b = await box(visualOf(page, 'charge'))
      return b.x + b.width <= c.x + c.width && b.x >= c.x
    })
    .toBe(true)
  await scanA11y(page, 'selected activity scrolled into view')
})

test('a markers-only change repaints without re-import and extends the legend', async ({
  page,
}) => {
  await mockBff(page, { active: ['task1'], deadLetter: ['charge'] })
  await page.goto('/inspect/eng1/inst-failed')
  await expect(visualOf(page, 'theStart')).toBeVisible()

  const legend = page.locator('.diagram-legend li')
  await expect(legend).toHaveCount(2)
  expect(await legend.evaluateAll((items) => items.map((li) => li.dataset.kind))).toEqual([
    'activeActivityIds',
    'deadLetterActivityIds',
  ])

  await settledViewbox(page)
  await pan(page, 60)
  const kept = await viewbox(page)
  const startNode = await visualOf(page, 'theStart').elementHandle()
  if (startNode === null) throw new Error('no start shape')

  await page.getByRole('button', { name: /Compare with a sibling/ }).click()
  await expect(page.locator('.diagram-canvas g.marker-diverge-subject')).toHaveCount(1)
  await expect(page.locator('.diagram-canvas g.marker-diverge-sibling')).toHaveCount(1)
  expect(await startNode.evaluate((node) => node.isConnected)).toBe(true)
  expect(await viewbox(page)).toBe(kept)

  await expect(legend).toHaveCount(4)
  expect(await legend.evaluateAll((items) => items.map((li) => li.dataset.kind))).toEqual([
    'activeActivityIds',
    'deadLetterActivityIds',
    'subjectOnlyActivityIds',
    'siblingOnlyActivityIds',
  ])
  await scanA11y(page, 'diagram with divergence overlay and legend')
})

test('no markers, no legend', async ({ page }) => {
  await mockBff(page, { active: [], deadLetter: [] })
  await page.goto('/inspect/eng1/inst-failed')
  await expect(visualOf(page, 'theStart')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Fit', exact: true })).toBeVisible()
  await expect(page.locator('.diagram-legend')).toHaveCount(0)
  await scanA11y(page, 'diagram without markers')
})
