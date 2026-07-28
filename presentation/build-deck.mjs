import PptxGenJS from 'pptxgenjs'

// ---- design tokens (light corporate) ------------------------------------
const NAVY = '1F3864'
const BLUE = '2E75B6'
const TINT = 'DEEBF7'
const TEXT = '3B3B3B'
const MUTED = '767171'
const RULE = 'D9D9D9'
const WHITE = 'FFFFFF'
const TITLE_FONT = 'Calibri Light'
const BODY_FONT = 'Calibri'

const W = 13.333
const M = 0.75 // left/right margin

const pptx = new PptxGenJS()
// LAYOUT_WIDE == 13.333 x 7.5in (PowerPoint's modern 16:9 default).
// NOT LAYOUT_16x9 — that is pptxgenjs's legacy 10 x 5.625in page and every
// coordinate below assumes the 13.333in width.
pptx.layout = 'LAYOUT_WIDE'
pptx.author = 'Workflow platform team'
pptx.company = 'Workflow platform team'
pptx.title = 'Process Inspector — delivery review'

let slideNo = 0

function chrome(s, { footer = true } = {}) {
  if (!footer) return
  slideNo++
  s.addText('Process Inspector — delivery review', {
    x: M, y: 6.95, w: 6, h: 0.3, fontFace: BODY_FONT, fontSize: 9, color: MUTED,
  })
  s.addText(String(slideNo), {
    x: W - M - 0.6, y: 6.95, w: 0.6, h: 0.3, align: 'right',
    fontFace: BODY_FONT, fontSize: 9, color: MUTED,
  })
}

function head(s, title, kicker) {
  if (kicker) {
    s.addText(kicker.toUpperCase(), {
      x: M, y: 0.42, w: W - 2 * M, h: 0.25,
      fontFace: BODY_FONT, fontSize: 10.5, bold: true, color: BLUE, charSpacing: 1.4,
    })
  }
  s.addText(title, {
    x: M, y: kicker ? 0.68 : 0.55, w: W - 2 * M, h: 0.62,
    fontFace: TITLE_FONT, fontSize: 28, color: NAVY,
  })
  s.addShape(pptx.ShapeType.rect, {
    x: M, y: kicker ? 1.38 : 1.25, w: 1.5, h: 0.045, fill: { color: BLUE },
  })
}

// items: string | {b, t} | {b, t, sub}
function bullets(s, items, { y = 1.95, w = W - 2 * M, x = M, size = 16, gap = 13 } = {}) {
  const runs = []
  items.forEach((it) => {
    const o = typeof it === 'string' ? { t: it } : it
    // NOTE: do NOT use pptxgenjs `bullet` here. It is a paragraph property, so
    // a bold lead-in run and its body run each become their own bulleted
    // paragraph — the item breaks in two. The dash is drawn inline instead.
    const para = {
      fontFace: BODY_FONT, fontSize: size, color: TEXT,
      paraSpaceAfter: gap, lineSpacingMultiple: 1.12,
      breakLine: true,
    }
    if (o.b) runs.push({ text: '–  ' + o.b, options: { ...para, bold: true, color: NAVY, breakLine: !o.t } })
    if (o.t) runs.push({ text: (o.b ? ' ' : '–  ') + o.t, options: para })
  })
  s.addText(runs, { x, y, w: w - 0.3, h: 4.7, valign: 'top' })
}

function tile(s, { x, y, w, h, big, label, sub }) {
  s.addShape(pptx.ShapeType.roundRect, {
    x, y, w, h, rectRadius: 0.06,
    fill: { color: 'F7FAFD' }, line: { color: TINT, width: 1 },
  })
  s.addText(big, {
    x, y: y + 0.16, w, h: 0.62, align: 'center',
    fontFace: TITLE_FONT, fontSize: 30, bold: true, color: NAVY,
  })
  s.addText(label, {
    x: x + 0.12, y: y + 0.8, w: w - 0.24, h: 0.5, align: 'center', valign: 'top',
    fontFace: BODY_FONT, fontSize: 11.5, color: TEXT,
  })
  if (sub) {
    s.addText(sub, {
      x: x + 0.12, y: y + 1.22, w: w - 0.24, h: 0.3, align: 'center',
      fontFace: BODY_FONT, fontSize: 9.5, italic: true, color: MUTED,
    })
  }
}

function newSlide() {
  const s = pptx.addSlide()
  s.background = { color: WHITE }
  return s
}

// ---- 1. title -----------------------------------------------------------
{
  const s = newSlide()
  s.background = { color: WHITE }
  s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: 0.28, h: 7.5, fill: { color: NAVY } })
  s.addText('Process Inspector', {
    x: 1.1, y: 2.25, w: 11, h: 0.95, fontFace: TITLE_FONT, fontSize: 46, color: NAVY,
  })
  s.addText('How we built it — the research, the delivery approach, and what we change next', {
    x: 1.1, y: 3.25, w: 10.6, h: 0.6, fontFace: BODY_FONT, fontSize: 18, color: TEXT,
  })
  s.addShape(pptx.ShapeType.rect, { x: 1.12, y: 4.05, w: 1.8, h: 0.045, fill: { color: BLUE } })
  s.addText('Delivery review · 6 – 27 July 2026 · workflow platform team', {
    x: 1.1, y: 4.35, w: 10, h: 0.4, fontFace: BODY_FONT, fontSize: 13, color: MUTED,
  })
}

// ---- 2. what we built ---------------------------------------------------
{
  const s = newSlide()
  head(s, 'One console for every stuck process', 'What we built')
  bullets(s, [
    { b: 'The job it does:', t: 'an on-call engineer finds, diagnoses and fixes a failed process instance in one place — instead of an SSH session, a database query and a guess.' },
    { b: 'Multi-engine by design:', t: 'all Flowable environments (test, staging, production) in one search, each labeled with its environment colour.' },
    { b: 'Safe by construction:', t: 'read-only by default; every fix is role-gated, requires a stated reason, and is written to an audit trail the incumbent tool does not have.' },
    { b: 'Zero footprint on the engines:', t: 'integrates strictly through the public Flowable REST API — no database access, no plugin, no agent to install.' },
    { b: 'Status today:', t: 'live on the demo host, released as versioned container images, with a nightly full-system test run.' },
  ])
  chrome(s)
}

// ---- 3. numbers ---------------------------------------------------------
{
  const s = newSlide()
  head(s, 'Three weeks, from empty repository to released product', 'Outcome')
  const cols = 3, gw = (W - 2 * M - 0.4 * (cols - 1)) / cols
  const data = [
    ['22', 'calendar days', '6 – 27 July 2026'],
    ['119', 'pull requests merged', 'every one green in CI first'],
    ['111k', 'lines of product code', '70k backend · 41k frontend'],
    ['2,100+', 'automated tests', 'incl. 49 against real engines'],
    ['117', 'tracked requirements', 'each traced to code and tests'],
    ['14,700', 'lines of living specification', 'kept in step with the code'],
  ]
  data.forEach((d, i) => {
    tile(s, {
      x: M + (i % cols) * (gw + 0.4),
      y: 1.75 + Math.floor(i / cols) * 2.0,
      w: gw, h: 1.72, big: d[0], label: d[1], sub: d[2],
    })
  })
  s.addText('Delivered by a small team working with AI coding agents under a fixed set of engineering rules.', {
    x: M, y: 6.05, w: W - 2 * M, h: 0.35, fontFace: BODY_FONT, fontSize: 12.5, italic: true, color: MUTED,
  })
  chrome(s)
}

// ---- 4. timeline --------------------------------------------------------
{
  const s = newSlide()
  head(s, 'How the three weeks were spent', 'Timeline')
  const rows = [
    ['Day 1', 'Research and specification only', 'Two market studies, an expert panel and a 14-seat review board — no product code written.'],
    ['Week 1', 'Core product (M0 – M4)', 'Engine registry, cross-engine search, instance detail with diagram, corrective actions, audit trail, roles.'],
    ['Week 2', 'v1 close-out and release', 'Bulk operations, hardening, container release pipeline, live demo environment, parallel CI.'],
    ['Week 3', 'Demand-driven v2', 'Shared team views, deep paging, runtime engine administration, identity and access, incident ledger.'],
    ['Week 4', 'Adversarial review and fix', 'Whole-application review; 17 findings implemented and merged; framework versions brought current.'],
  ]
  rows.forEach((r, i) => {
    const y = 1.72 + i * 0.98
    s.addShape(pptx.ShapeType.roundRect, {
      x: M, y, w: 1.55, h: 0.72, rectRadius: 0.08, fill: { color: NAVY },
    })
    s.addText(r[0], {
      x: M, y, w: 1.55, h: 0.72, align: 'center', valign: 'middle',
      fontFace: BODY_FONT, fontSize: 14, bold: true, color: WHITE,
    })
    s.addText(r[1], {
      x: M + 1.8, y: y - 0.02, w: W - M - 1.8 - M, h: 0.34,
      fontFace: BODY_FONT, fontSize: 14.5, bold: true, color: NAVY,
    })
    s.addText(r[2], {
      x: M + 1.8, y: y + 0.3, w: W - M - 1.8 - M, h: 0.42,
      fontFace: BODY_FONT, fontSize: 12.5, color: TEXT,
    })
    if (i < rows.length - 1) {
      s.addShape(pptx.ShapeType.rect, { x: M + 1.8, y: y + 0.85, w: W - 2 * M - 1.8, h: 0.008, fill: { color: RULE } })
    }
  })
  chrome(s)
}

// ---- 5. research --------------------------------------------------------
{
  const s = newSlide()
  head(s, 'We started with a market study, not a prototype', 'How we started · research')
  bullets(s, [
    { b: 'Study 1 — the incumbent.', t: 'A full feature and permission inventory of IBM BAW Process Inspector, together with its documented weaknesses: no saved searches, no search by instance ID or variable, manual-only bulk, and no audit trail of administrator actions.' },
    { b: 'Study 2 — the field.', t: 'Seven comparable tools (Camunda, Temporal, Flowable Control, Conductor, Airflow, AWS Step Functions, Azure Durable Functions) reduced to twelve patterns the whole industry has converged on.' },
    { b: 'Study 3 — the platform.', t: 'What the Flowable REST API can and cannot actually do — established by experiment against a real engine, before anything was promised.' },
    { b: 'Output of day one:', t: 'a specification with a glossary, numbered design principles and an explicit list of things we would not build.' },
  ])
  chrome(s)
}

// ---- 6. panel method ----------------------------------------------------
{
  const s = newSlide()
  head(s, 'Then we reviewed the specification like a board', 'How we started · review')
  bullets(s, [
    { b: 'Round 1 — four expert seats.', t: 'Workflow-engine specialist, senior support engineer, lead developer and UX expert each reviewed independently, then the findings were reconciled into one specification.' },
    { b: 'Round 2 — a fourteen-seat review board.', t: 'Product owner, business analyst, architect, DevOps, test manager, usability experts and day-shift, L2 and L3 support staff. Roughly 130 findings.' },
    { b: 'Reconciliation, not averaging.', t: 'Where seats disagreed the conflict was recorded and decided in writing — including two features cut from the first release on the product owner’s argument.' },
    { b: 'Result:', t: '117 numbered, prioritised requirements and 3 recorded architecture decisions. Every later change refers back to a requirement number.' },
    { b: 'Cost:', t: 'about one day of elapsed time.' },
  ])
  chrome(s)
}

// ---- 7. what research bought --------------------------------------------
{
  const s = newSlide()
  head(s, 'What that day of research paid for', 'How we started · return')
  bullets(s, [
    { b: 'Six correctness traps found before any product code.', t: 'Most serious: the engine returns failed-job lists ten at a time, so a naive implementation lets a broken process instance appear healthy. Every competitor-grade tool has to solve this; we knew it on day one.' },
    { b: 'Four expensive dead ends refused up front.', t: 'A query language, an undo-able change preview, live streaming search and engine-internals reporting were all specified away — the engine cannot support them, and each would have cost weeks to discover.' },
    { b: 'Business blind spots caught early.', t: 'The first specification had no success measures, no release gate and no rollout plan. The product owner’s seat added them before build, not after pilot.' },
    { b: '“Adjectives where numbers belong.”', t: 'Every limit, timeout and threshold was rewritten as a testable number — which is why they could later be enforced automatically.' },
  ])
  chrome(s)
}

// ---- 8. operating model -------------------------------------------------
{
  const s = newSlide()
  head(s, 'The specification is the system of record', 'Approach · operating model')
  bullets(s, [
    { b: 'Three documents, three questions.', t: 'What the product does · how and why it is built that way · when each piece lands. Nothing lives in someone’s head.' },
    { b: 'The lockstep rule.', t: 'A change in behaviour updates the matching document section in the same change. A change that does not is not finished.' },
    { b: 'Traceability.', t: 'One matrix links every requirement to the code that implements it and the test that proves it — which is what makes an audit or a compliance question answerable in minutes.' },
    { b: 'Why it matters commercially.', t: 'Any contributor — a new hire or an AI agent — reads the rule instead of guessing, and reviews argue about the decision rather than the diff. This is the single practice that made the pace possible.' },
  ])
  chrome(s)
}

// ---- 9. worked best 1 ---------------------------------------------------
{
  const s = newSlide()
  head(s, 'Small, complete slices — never a big-bang integration', 'What worked best · 1 of 3')
  bullets(s, [
    { b: 'Every feature was cut into four to six slices,', t: 'each one independently reviewable, releasable and demonstrable on its own.' },
    { b: '119 changes in 22 days', t: '— the average change stayed small enough to be reviewed properly rather than waved through.' },
    { b: 'No long-lived branches.', t: 'Work merged the day it was written, so two work streams never spent a week diverging.' },
    { b: 'Evidence:', t: 'the five largest second-phase features — shared team views, deep result paging, runtime engine administration, identity and access, and the incident ledger — each went from design lock to complete within one to two days.' },
    { b: 'The trade we accepted:', t: 'more design decisions up front, in exchange for near-zero integration risk at the end.' },
  ])
  chrome(s)
}

// ---- 10. worked best 2 --------------------------------------------------
{
  const s = newSlide()
  head(s, 'Guardrails are what make speed safe', 'What worked best · 2 of 3')
  bullets(s, [
    { b: 'A short list of non-negotiable rules', t: 'covers the ways this product could do real damage: never write to an engine’s database, every corrective action audited and role-gated, database schema only through reviewed migrations.' },
    { b: 'The interface between frontend and backend is generated, not hand-written.', t: 'The two halves are structurally incapable of drifting apart — a whole class of defect simply cannot occur.' },
    { b: 'Formatting and code standards are build failures,', t: 'not review comments. Nobody spends attention on style.' },
    { b: '“Done” means the build is green on the merged commit', t: '— a successful upload is not success. This one definition removed most of the “it works on my machine” traffic.' },
  ])
  chrome(s)
}

// ---- 11. worked best 3 --------------------------------------------------
{
  const s = newSlide()
  head(s, 'Automating the feedback loop, not just the typing', 'What worked best · 3 of 3')
  bullets(s, [
    { b: 'Build and test time: about 25 minutes → 3 minutes 45.', t: 'Achieved by running the suite across six parallel build machines. Fast feedback changes behaviour; slow feedback gets skipped.' },
    { b: 'A realistic environment on demand.', t: 'Real Flowable engines start in containers for the tests. Engine behaviour is never faked — which is how the truncation traps stay caught.' },
    { b: 'Usability tested before shipping.', t: 'Scripted incident missions are driven against the real user interface by test agents; a surface ships when the mission success rate clears an agreed bar.' },
    { b: 'Independent review on every design and change.', t: 'Separate AI review models act as a second and third opinion at negligible cost — they caught real defects the author had signed off.' },
  ])
  chrome(s)
}

// ---- 12. quality evidence -----------------------------------------------
{
  const s = newSlide()
  head(s, 'The evidence behind “it works”', 'Quality')
  const cols = 4, gw = (W - 2 * M - 0.35 * (cols - 1)) / cols
  ;[
    ['1,231', 'backend tests', ''],
    ['49', 'against real engines', 'no simulated engine'],
    ['808', 'frontend unit tests', ''],
    ['64', 'browser journeys', 'incl. accessibility'],
  ].forEach((d, i) => {
    tile(s, { x: M + i * (gw + 0.35), y: 1.75, w: gw, h: 1.62, big: d[0], label: d[1], sub: d[2] })
  })
  bullets(s, [
    { b: 'Nightly full-system run', t: 'against the complete engine matrix, with a watchdog that raises an alarm if the nightly run itself fails to start.' },
    { b: 'Every corrective action is proven against a genuinely failing process,', t: 'not a stubbed response — the failure modes we advertise are the ones we have actually reproduced.' },
    { b: 'Accessibility and visual checks run in the same pipeline', t: 'as the functional tests, so a regression blocks the merge.' },
  ], { y: 3.72, size: 14, gap: 10 })
  chrome(s)
}

// ---- 13. what cost us ---------------------------------------------------
{
  const s = newSlide()
  head(s, 'What cost us time — the honest list', 'Lessons')
  bullets(s, [
    { b: 'Documentation drift.', t: 'One sprint shipped seven fixes without updating the plan or closing the ticket. It was found by a human re-reading the document — the process did not catch it.' },
    { b: 'A shared working copy.', t: 'Parallel work streams changed the same checkout underneath each other, producing confusing failures that were not code defects.' },
    { b: 'Collisions with unrelated projects on the same host.', t: 'A network port held by another team’s container looked like an intermittent test failure for a day before it was diagnosed.' },
    { b: 'Review substitution under quota limits.', t: 'When the designated review model was unavailable, one run improvised a different reviewer and effectively graded its own work. No defect resulted, but the control had failed.' },
    { b: 'Implied steps get skipped.', t: 'Automated contributors reliably omitted one formatting check whenever it was not named explicitly in the task.' },
  ], { size: 14.5, gap: 10 })
  chrome(s)
}

// ---- 14. improvements ---------------------------------------------------
{
  const s = newSlide()
  head(s, 'Six changes to the way we work', 'Improvements')
  const items = [
    ['Make the document update a merge check', 'Drift becomes impossible rather than unlikely; removes the reliance on a reviewer’s memory.'],
    ['One isolated working copy per work stream', 'Enforced by tooling, not convention — removes a whole class of phantom failure.'],
    ['Reserve a host port range per project', 'Checked automatically before a build machine starts; ends cross-project interference.'],
    ['Named reviewers only', 'If the designated reviewer is unavailable the merge waits. No self-grading, ever.'],
    ['Every gate spelled out in the task brief', 'Assume nothing is implied — the cheapest fix on this list.'],
    ['Track deferred human actions as owned items', 'Backup activation and production cut-over are named owners with dates, not footnotes in a plan.'],
  ]
  const cols = 2, gw = (W - 2 * M - 0.45) / cols
  items.forEach((it, i) => {
    const x = M + (i % cols) * (gw + 0.45)
    const y = 1.75 + Math.floor(i / cols) * 1.62
    s.addShape(pptx.ShapeType.roundRect, {
      x, y, w: gw, h: 1.42, rectRadius: 0.06, fill: { color: 'F7FAFD' }, line: { color: TINT, width: 1 },
    })
    s.addShape(pptx.ShapeType.ellipse, { x: x + 0.22, y: y + 0.22, w: 0.42, h: 0.42, fill: { color: BLUE } })
    s.addText(String(i + 1), {
      x: x + 0.22, y: y + 0.22, w: 0.42, h: 0.42, align: 'center', valign: 'middle',
      fontFace: BODY_FONT, fontSize: 13, bold: true, color: WHITE,
    })
    s.addText(it[0], {
      x: x + 0.78, y: y + 0.2, w: gw - 1.0, h: 0.46,
      fontFace: BODY_FONT, fontSize: 14, bold: true, color: NAVY, valign: 'middle',
    })
    s.addText(it[1], {
      x: x + 0.78, y: y + 0.66, w: gw - 1.0, h: 0.62,
      fontFace: BODY_FONT, fontSize: 11.5, color: TEXT,
    })
  })
  chrome(s)
}

// ---- 15. next -----------------------------------------------------------
{
  const s = newSlide()
  head(s, 'What we would scale — and what we need from you', 'Next')
  bullets(s, [
    { b: 'The method transfers to other products.', t: 'Research and panel review first · numbered requirements · thin slices · hard automated gates. None of it is specific to this product.' },
    { b: 'Highest-leverage practices to reuse elsewhere:', t: 'generated interface contracts, a realistic containerised test environment, and parallel build capacity.' },
    { b: 'Decisions we need sponsored:', t: 'three engines onboarded for the pilot including one production engine and its signed checklist; activation of the audit-store backup and recovery procedure; and the demand evidence that unlocks the remaining automation features.' },
    { b: 'The ask:', t: 'name the pilot owner and the three engines, and give us a date for the production onboarding review.' },
  ])
  s.addShape(pptx.ShapeType.rect, { x: M, y: 5.55, w: W - 2 * M, h: 0.02, fill: { color: RULE } })
  s.addText('Questions', {
    x: M, y: 5.8, w: 6, h: 0.5, fontFace: TITLE_FONT, fontSize: 22, color: NAVY,
  })
  chrome(s)
}

const out = process.argv[2]
await pptx.writeFile({ fileName: out })
console.log('wrote', out)
