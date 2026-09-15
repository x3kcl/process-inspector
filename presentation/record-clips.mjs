// Feature-video recorder for the deck. Drives the LIVE DEMO (pi.naumann.cloud) with
// Playwright and records one .webm per clip into .video/raw/.
//
//   node presentation/record-clips.mjs              # every clip, in order
//   node presentation/record-clips.mjs fix ledger   # just those
//
// Notes that cost a re-record if forgotten:
//   * Playwright films the page, NOT the pointer — there is no cursor in the output. The
//     init script below paints one and every helper moves it, so clicks are legible.
//   * The video is written on context.close(), so a thrown step must still close the
//     context or the clip is lost. Each clip is wrapped accordingly.
//   * Size is fixed 1600x900 (16:9) to match the 13.333x7.5in slide without letterboxing.
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import fs from 'node:fs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const require = createRequire(path.join(HERE, '../frontend/package.json'))
const { chromium } = require('playwright')

const BASE = process.env.PI_DEMO_URL ?? 'https://pi.naumann.cloud'
const RAW = path.join(HERE, '.video/raw')
const SIZE = { width: 1600, height: 900 }
const CHROME = process.env.PI_CHROME ??
  path.join(process.env.HOME, '.cache/ms-playwright/chromium-1234/chrome-linux64/chrome')

// ---- synthetic pointer ---------------------------------------------------------------
const CURSOR = `
  const d = document.createElement('div'); d.id='__cur';
  d.style.cssText = 'position:fixed;z-index:2147483647;width:22px;height:22px;margin:-11px 0 0 -11px;' +
    'border-radius:50%;background:rgba(46,117,182,.35);border:2px solid #2E75B6;pointer-events:none;' +
    'transition:transform .08s linear;left:0;top:0;opacity:0';
  const add = () => { if (document.body && !document.getElementById('__cur')) document.body.appendChild(d) };
  document.addEventListener('DOMContentLoaded', add); add();
  window.__cur = (x, y) => { add(); d.style.opacity='1'; d.style.transform='translate('+x+'px,'+y+'px)' };
  window.__ping = () => { d.animate([{boxShadow:'0 0 0 0 rgba(46,117,182,.55)'},
    {boxShadow:'0 0 0 22px rgba(46,117,182,0)'}], {duration:450}) };
`

const wait = (ms) => new Promise((r) => setTimeout(r, ms))

async function moveTo(page, loc) {
  const box = await loc.first().boundingBox()
  if (!box) throw new Error('no bounding box for ' + loc)
  const x = Math.round(box.x + box.width / 2)
  const y = Math.round(box.y + box.height / 2)
  await page.mouse.move(x, y, { steps: 22 })
  await page.evaluate(([x, y]) => window.__cur?.(x, y), [x, y])
  await wait(420)
  return { x, y }
}

async function click(page, loc, { settle = 1100 } = {}) {
  await loc.first().scrollIntoViewIfNeeded().catch(() => {})
  await wait(250)
  await moveTo(page, loc)
  await page.evaluate(() => window.__ping?.())
  await wait(160)
  await loc.first().click()
  await wait(settle)
}

async function type(page, loc, text, { delay = 55 } = {}) {
  await moveTo(page, loc)
  await loc.first().click()
  await loc.first().fill('')
  await loc.first().type(text, { delay })
  await wait(500)
}

// Park the pointer on something while the viewer reads it. Scrolls the anchor into view
// first: page.mouse.wheel does NOT move the instance-detail page (it scrolls an inner
// container), so wheel-based clips silently filmed the un-scrolled page.
async function read(page, loc, ms = 1800) {
  if (loc) {
    await loc.first().scrollIntoViewIfNeeded().catch(() => {})
    await wait(300)
    await moveTo(page, loc).catch(() => {})
  }
  await wait(ms)
}

async function signIn(page, user = 'operator') {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('dev')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForTimeout(4500)
}

// The instance-detail page does NOT scroll: the document is exactly viewport-height and the
// tab panel is an inner `section.tab-body` that the diagram squeezes to ~24px. Collapsing the
// diagram is what gives the tabs room — so every tab clip shows the diagram first, then hides
// it, which is also how an operator actually works the page.
async function hideDiagram(page) {
  const btn = page.getByRole('button', { name: /Hide diagram/ })
  if (await btn.count()) await click(page, btn, { settle: 1400 })
}

// ---- clips ----------------------------------------------------------------------------
const targets = JSON.parse(fs.readFileSync(path.join(HERE, '.video-targets.json'), 'utf8'))
const detailUrl = (t) => `${BASE}/inspect/${t.engine}/${t.id}`

const clips = {}

// 1. Stage 0 — what is broken, how much, where, in zero keystrokes.
clips.triage = async (page) => {
  await signIn(page, 'operator')
  await read(page, page.getByText('Engine A (demo)').first(), 2600)            // health strip
  await read(page, page.getByRole('link', { name: /FAILED \d+ instances/ }), 2000)
  await read(page, page.getByText(/as of /).first(), 1700)                     // honesty stamp
  await page.mouse.wheel(0, 420); await wait(1200)
  await read(page, page.getByText('ArithmeticException').first(), 2600)        // error-class card
  await read(page, page.getByText(/Error while evaluating expression/).first(), 2000)
  await page.mouse.wheel(0, 380); await wait(1400)
  await read(page, page.getByRole('link', { name: /Engine 7 \(Flowable 7.x\)/ }).first(), 2400)
  await page.mouse.wheel(0, 700); await wait(2600)                            // leak views
}

// 2. One class -> the grid. The whole search state lives in the URL.
clips.search = async (page) => {
  await signIn(page, 'operator')
  await page.mouse.wheel(0, 420); await wait(900)
  await click(page, page.getByRole('link', { name: /^40 instances$/ }), { settle: 5000 })
  await read(page, page.locator('body'), 2800)
  await page.mouse.wheel(0, 300); await wait(2400)
  await page.mouse.wheel(0, 400); await wait(2600)
}

// 3. Stage 2 — why is THIS one stuck.
clips.detail = async (page) => {
  await signIn(page, 'operator')
  await page.goto(detailUrl(targets['PAY-FIX-100']), { waitUntil: 'domcontentloaded' })
  await wait(6500)
  await read(page, page.getByText(/Error while evaluating expression/).first(), 2800)  // why-stuck
  await read(page, page.getByRole('button', { name: /Migrate/ }).first(), 2400)        // greyed + gate
  await read(page, page.getByRole('button', { name: /Terminate \/ delete/ }).first(), 2200)
  await read(page, page.locator('text=Charge payment').first(), 3000)                  // diagram ⚠
  await hideDiagram(page)
  await click(page, page.getByRole('tab', { name: 'Errors & Jobs' }), { settle: 3400 })
  await read(page, page.getByText(/External worker/).first(), 3200)                    // the lanes
  await click(page, page.getByRole('tab', { name: 'Variables' }), { settle: 2400 })
  await read(page, page.getByText('customer').first(), 2600)                           // typed ledger
  await read(page, page.getByText('divisor').first(), 3400)
}

// 4. The sibling diff — why did this one fail when its twin succeeded.
//    Entered by the why-stuck strip's own button; the ?tab=compare deep link leaves the
//    Variables tab selected, so clicking is both correct and more demonstrative.
clips.compare = async (page) => {
  await signIn(page, 'operator')
  await page.goto(detailUrl(targets['PAY-FIX-100']), { waitUntil: 'domcontentloaded' })
  await wait(6500)
  await click(page, page.getByRole('button', { name: /Compare with a sibling/ }), { settle: 6000 })
  await read(page, page.locator('text=Charge payment').first(), 3000)                  // ▲/△ overlay
  await hideDiagram(page)
  await read(page, page.getByText(/auto-suggested/).first(), 3400)
  await read(page, page.getByText(/differing/).first(), 3600)
  await read(page, page.getByText(/identical/).first(), 2600)
  await read(page, page.getByRole('cell', { name: 'divisor' }).first(), 3600)   // the punchline
}

// 5. The fix, with the guard rails on camera — edit the variable, then retry the job.
//    This one really mutates the demo: divisor 0 -> 2, retry, instance completes.
//    The retry is taken from the success toast's own "Retry the failed job?" button — that
//    is the documented single path (offered, never automatic). Clicking the lane's Retry AND
//    the toast's retries twice and ends the clip on a red "nothing happened" error.
clips.fix = async (page) => {
  await signIn(page, 'operator')
  await page.goto(detailUrl(targets['PAY-FIX-250']), { waitUntil: 'domcontentloaded' })
  await wait(6500)
  await hideDiagram(page)
  const row = page.locator('tr', { hasText: 'divisor' }).first()
  await click(page, row.getByRole('button', { name: /edit/ }), { settle: 2000 })
  const box = page.getByRole('spinbutton').or(page.getByRole('textbox')).last()
  await type(page, box, '2')
  await read(page, page.getByText(/will be stored as/).first(), 1800)
  await click(page, page.getByRole('button', { name: /Review change/ }), { settle: 2600 })
  await read(page, page.getByText(/server value re-checked/).first(), 3600)   // compare-and-set
  await read(page, page.getByText(/RECOVERABLE/).first(), 2600)
  await click(page, page.getByRole('button', { name: /^Change divisor from/ }), { settle: 3600 })
  await read(page, page.getByText(/changed from 0 to 2/).first(), 3000)       // outcome toast
  await click(page, page.getByRole('button', { name: /Retry the failed job/ }), { settle: 6000 })
  await read(page, page.getByText(/COMPLETED/).first(), 4000)                 // verified, on camera
}

// 6. Bulk over a whole error class, with per-item outcomes.
//    Two traps paid for in re-records:
//      * the reason field is an unlabelled <textarea>; getByRole('textbox').last() grabs the
//        Ticket ID input, leaves the reason empty, and the dispatch button stays locked
//        behind "Reason too short" while the click fights the modal backdrop;
//      * clicking the Operations drawer straight after dispatch blocks for ~60s on the
//        closing modal's backdrop. Reload first — the job is already persisted, and the
//        drawer is the point of the shot.
clips.bulk = async (page) => {
  await signIn(page, 'operator')
  await page.mouse.wheel(0, 420); await wait(1200)
  await click(page, page.getByRole('button', { name: 'Retry group' }).first(), { settle: 2800 })
  const modal = page.locator('.modal, [role=dialog]').first()
  await read(page, modal.getByText(/resolved server-side at dispatch/).first(), 3600)
  await read(page, modal.getByText(/Mostly safe/).first(), 2600)
  await read(page, modal.getByText(/Capped at 200 instances/).first(), 2600)
  await type(page, modal.locator('textarea').first(),
             'payment divisor corrected — re-queueing the class', { delay: 34 })
  await read(page, modal.getByRole('button', { name: /^Retry group —/ }), 1800)
  await click(page, modal.getByRole('button', { name: /^Retry group —/ }), { settle: 4500 })

  await page.reload({ waitUntil: 'domcontentloaded' })
  await wait(5000)
  const drawer = page.getByRole('button', { name: /^Operations/ }).first()
  await moveTo(page, drawer)
  await page.evaluate(() => window.__ping?.())
  await drawer.click()
  await wait(2600)
  await read(page, page.getByText(/dispatched/).first(), 5000)   // per-item totals, this job first
}

// 7. Who did what, when, why — the handover artifact.
clips.audit = async (page) => {
  await signIn(page, 'operator')
  await page.goto(BASE + '/audit', { waitUntil: 'domcontentloaded' })
  await wait(6000)
  await read(page, page.locator('body'), 3000)
  await click(page, page.getByRole('button', { name: /My shift/ }).first(), { settle: 3500 })
  await read(page, page.locator('body'), 3000)
  await page.mouse.wheel(0, 350); await wait(3000)
}

// 8. The research half — persisted incidents, episodes/MTTR, and the self-heal evidence badge.
//    The incident card's link text is the error MESSAGE (href /incidents/N), not the
//    exception class — clicking the class text hits the card, not the link.
clips.ledger = async (page) => {
  await signIn(page, 'operator')
  await page.goto(BASE + '/incidents', { waitUntil: 'domcontentloaded' })
  await wait(7000)
  await read(page, page.getByText(/Persisted history for every failure class/).first(), 3600)
  await read(page, page.getByText(/self-heal history/).first(), 3600)          // R2 evidence badge
  await read(page, page.getByText(/first seen/).first(), 2600)
  await click(page, page.getByRole('link', { name: /Error while evaluating expression/ }).first(),
              { settle: 7000 })
  await read(page, page.locator('body'), 3000)
  await read(page, page.getByText(/[Ee]pisode/).first(), 3600)                 // episodes + MTTR
  await read(page, page.getByText(/bulk retr/i).first(), 3600)                 // remediation join
}

// registry order = presentation order
const ORDER = ['triage', 'search', 'detail', 'compare', 'fix', 'bulk', 'audit', 'ledger']

const want = process.argv.slice(2).length ? process.argv.slice(2) : ORDER
fs.mkdirSync(RAW, { recursive: true })

const browser = await chromium.launch({ executablePath: CHROME })
for (const name of want) {
  if (!clips[name]) { console.error('no such clip:', name); continue }
  const dir = path.join(RAW, name)
  fs.rmSync(dir, { recursive: true, force: true })
  fs.mkdirSync(dir, { recursive: true })
  const ctx = await browser.newContext({
    viewport: SIZE, recordVideo: { dir, size: SIZE }, deviceScaleFactor: 1,
  })
  await ctx.addInitScript(CURSOR)
  const page = await ctx.newPage()
  let err = null
  try { await clips[name](page) } catch (e) { err = e }
  await wait(900)
  await ctx.close()                       // <- writes the webm
  const f = fs.readdirSync(dir).find((x) => x.endsWith('.webm'))
  if (f) fs.renameSync(path.join(dir, f), path.join(RAW, name + '.webm'))
  fs.rmSync(dir, { recursive: true, force: true })
  console.log(err ? `✗ ${name}: ${err.message}` : `✓ ${name}`)
}
await browser.close()
