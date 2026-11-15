// #674 (study F5): the bpmn-js canvas writes its colours as inline SVG styles, so the theme
// tokens never reached it — dark theme showed dark-on-dark labels and flows. Hermetic: the
// instance page mounts the real bpmn-js canvas; the theme is seeded via localStorage like
// dark-theme.spec.ts. Contrast is measured on computed styles of the real SVG nodes.
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
             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
             targetNamespace="smoke">
  <process id="orderFlow" name="Order flow" isExecutable="true">
    <startEvent id="theStart" name="Order received"/>
    <userTask id="approveTask" name="Approve order"/>
    <serviceTask id="chargeTask" name="Charge card"/>
    <serviceTask id="plainTask" name="Plain step"/>
    <endEvent id="theEnd" name="Done"/>
    <sequenceFlow id="f1" sourceRef="theStart" targetRef="approveTask"/>
    <sequenceFlow id="f2" sourceRef="approveTask" targetRef="chargeTask"/>
    <sequenceFlow id="f3" sourceRef="chargeTask" targetRef="plainTask"/>
    <sequenceFlow id="f4" sourceRef="plainTask" targetRef="theEnd"/>
  </process>
  <bpmndi:BPMNDiagram id="di">
    <bpmndi:BPMNPlane id="plane" bpmnElement="orderFlow">
      <bpmndi:BPMNShape id="s1" bpmnElement="theStart"><omgdc:Bounds x="0" y="40" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s2" bpmnElement="approveTask"><omgdc:Bounds x="80" y="28" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s3" bpmnElement="chargeTask"><omgdc:Bounds x="220" y="28" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s5" bpmnElement="plainTask"><omgdc:Bounds x="360" y="28" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s4" bpmnElement="theEnd"><omgdc:Bounds x="500" y="40" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="e1" bpmnElement="f1"><omgdi:waypoint x="36" y="58"/><omgdi:waypoint x="80" y="58"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e2" bpmnElement="f2"><omgdi:waypoint x="180" y="58"/><omgdi:waypoint x="220" y="58"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e3" bpmnElement="f3"><omgdi:waypoint x="320" y="58"/><omgdi:waypoint x="360" y="58"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e4" bpmnElement="f4"><omgdi:waypoint x="460" y="58"/><omgdi:waypoint x="500" y="58"/></bpmndi:BPMNEdge>
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

async function mount(page: Page, theme: 'light' | 'dark'): Promise<void> {
  await page.addInitScript((t) => {
    localStorage.setItem('inspector.theme', t)
  }, theme)
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const { pathname } = new URL(route.request().url())
      if (pathname === '/api/me') {
        await route.fulfill({ json: { role: 'OPERATOR', engineRoles: { eng1: 'OPERATOR' } } })
      } else if (pathname === '/api/engines') {
        await route.fulfill({ json: [ENGINE] })
      } else if (pathname.endsWith('/diagram')) {
        await route.fulfill({
          json: {
            xml: DIAGRAM_XML,
            activeActivityIds: ['approveTask'],
            deadLetterActivityIds: ['chargeTask'],
          },
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
  await page.goto('/inspect/eng1/inst-1')
  await expect(page.locator('.diagram-canvas [data-element-id="plainTask"]')).toBeVisible()
  // markers are applied after the import — wait for the active one
  await expect(page.locator('.diagram-canvas .marker-active')).toHaveCount(1)
}

type RGB = [number, number, number]

/** Computed property of the first match, parsed to rgb (alpha ignored; opaque theme colours). */
async function colour(page: Page, selector: string, prop: string): Promise<RGB> {
  return page
    .locator(selector)
    .first()
    .evaluate((el, p) => {
      const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(getComputedStyle(el).getPropertyValue(p))
      return m === null ? [-1, -1, -1] : [Number(m[1]), Number(m[2]), Number(m[3])]
    }, prop)
}

/** Nearest non-transparent background walking up from the canvas. */
async function canvasBg(page: Page): Promise<RGB> {
  return page
    .locator('.diagram-canvas')
    .first()
    .evaluate((start) => {
      for (let el: Element | null = start; el !== null; el = el.parentElement) {
        const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/.exec(
          getComputedStyle(el).backgroundColor,
        )
        if (m !== null && (m[4] === (undefined as string | undefined) || Number(m[4]) > 0)) {
          return [Number(m[1]), Number(m[2]), Number(m[3])]
        }
      }
      return [255, 255, 255]
    })
}

function lum([r, g, b]: RGB): number {
  const f = (c: number): number => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
}
function contrast(a: RGB, b: RGB): number {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

const EVENT_LABEL = '.diagram-canvas [data-element-id="theStart_label"] text'
const TASK_LABEL = '.diagram-canvas [data-element-id="plainTask"] text'
const FLOW = '.diagram-canvas [data-element-id="f4"] .djs-visual path'
const ARROW = '.diagram-canvas marker path'
const taskShape = (id: string): string =>
  `.diagram-canvas [data-element-id="${id}"] .djs-visual rect`

test('dark theme: event label, task label, flow and arrowhead are readable on the canvas', async ({
  page,
}) => {
  await mount(page, 'dark')
  const bg = await canvasBg(page)
  await scanA11y(page, 'instance page, dark theme, diagram mounted')
  expect(contrast(await colour(page, EVENT_LABEL, 'fill'), bg)).toBeGreaterThanOrEqual(4.5)
  expect(contrast(await colour(page, TASK_LABEL, 'fill'), bg)).toBeGreaterThanOrEqual(4.5)
  expect(contrast(await colour(page, FLOW, 'stroke'), bg)).toBeGreaterThanOrEqual(3)
  expect(contrast(await colour(page, ARROW, 'stroke'), bg)).toBeGreaterThanOrEqual(3)
  // task slab is no longer a bright white block on the dark canvas
  expect(contrast(await colour(page, taskShape('plainTask'), 'fill'), bg)).toBeLessThan(3)
})

test('dark theme: active and dead-letter activities differ from an unmarked one', async ({
  page,
}) => {
  await mount(page, 'dark')
  const key = async (id: string): Promise<string> =>
    JSON.stringify([
      await colour(page, taskShape(id), 'stroke'),
      await colour(page, taskShape(id), 'fill'),
    ])
  const plain = await key('plainTask')
  expect(await key('approveTask')).not.toEqual(plain)
  expect(await key('chargeTask')).not.toEqual(plain)
  expect(await key('approveTask')).not.toEqual(await key('chargeTask'))
})

test('light theme: diagram colours stay the bpmn-js defaults', async ({ page }) => {
  await mount(page, 'light')
  const bg = await canvasBg(page)
  expect(await colour(page, TASK_LABEL, 'fill')).toEqual([34, 36, 42])
  expect(await colour(page, FLOW, 'stroke')).toEqual([34, 36, 42])
  expect(await colour(page, taskShape('plainTask'), 'fill')).toEqual([255, 255, 255])
  expect(await colour(page, taskShape('plainTask'), 'stroke')).toEqual([34, 36, 42])
  expect(contrast(await colour(page, TASK_LABEL, 'fill'), bg)).toBeGreaterThanOrEqual(4.5)
})

test('the bpmn.io watermark is styled identically in both themes', async ({ browser }) => {
  const styles: string[] = []
  for (const theme of ['light', 'dark'] as const) {
    const page = await browser.newPage()
    await mount(page, theme)
    styles.push(
      await page
        .locator('.bjs-powered-by')
        .first()
        .evaluate((el) => {
          const s = getComputedStyle(el)
          return [s.display, s.visibility, s.opacity, s.position, s.filter, s.width, s.height].join(
            '|',
          )
        }),
    )
    await page.close()
  }
  expect(styles[1]).toEqual(styles[0])
})

test('dark theme: the label stays readable on an active-marked activity', async ({ page }) => {
  await mount(page, 'dark')
  for (const id of ['approveTask', 'chargeTask', 'plainTask']) {
    const label = await colour(page, `.diagram-canvas [data-element-id="${id}"] text`, 'fill')
    expect(contrast(label, await colour(page, taskShape(id), 'fill'))).toBeGreaterThanOrEqual(4.5)
  }
})
