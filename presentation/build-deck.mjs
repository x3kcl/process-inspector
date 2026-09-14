import PptxGenJS from 'pptxgenjs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import fs from 'node:fs'
import { CLIPS } from './clips.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))

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
pptx.title = 'Starting an AI project — Process Inspector as the worked example'

let slideNo = 0

function chrome(s, { footer = true } = {}) {
  if (!footer) return
  slideNo++
  s.addText('Starting an AI project · Process Inspector', {
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


// A full-bleed section divider — used to separate the three acts.
function section(kicker, title, sub) {
  const s = newSlide()
  s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: 7.5, fill: { color: NAVY } })
  s.addText(kicker.toUpperCase(), {
    x: 1.1, y: 2.42, w: 11, h: 0.3,
    fontFace: BODY_FONT, fontSize: 12, bold: true, color: '9DC3E6', charSpacing: 1.6,
  })
  // h must hold TWO lines at this size — a one-line box lets a wrapped title overrun the
  // rule below it (the rule then strikes through the second line).
  s.addText(title, {
    x: 1.1, y: 2.78, w: 11.1, h: 1.55, valign: 'top',
    fontFace: TITLE_FONT, fontSize: 36, color: WHITE,
  })
  s.addShape(pptx.ShapeType.rect, { x: 1.12, y: 4.52, w: 1.8, h: 0.045, fill: { color: '9DC3E6' } })
  if (sub) {
    s.addText(sub, {
      x: 1.1, y: 4.82, w: 10.8, h: 0.9, fontFace: BODY_FONT, fontSize: 16, color: 'D6E4F0',
    })
  }
  return s
}

// A clip slide: compact header, then the embedded mp4 at 16:9.
// addMedia EMBEDS the file, so the .pptx is self-contained — but it must exist at build
// time. Each mp4 opens on its own title card, which is also the poster frame PowerPoint
// shows before playback.
function videoSlide(clip) {
  const s = newSlide()
  const file = path.join(HERE, 'videos', clip.name + '.mp4')
  if (!fs.existsSync(file)) throw new Error('missing clip: ' + file + ' — run make-videos.sh')
  s.addText(clip.kicker.toUpperCase(), {
    x: M, y: 0.34, w: W - 2 * M, h: 0.24,
    fontFace: BODY_FONT, fontSize: 10.5, bold: true, color: BLUE, charSpacing: 1.4,
  })
  s.addText(clip.title, {
    x: M, y: 0.58, w: W - 2 * M, h: 0.5, fontFace: TITLE_FONT, fontSize: 24, color: NAVY,
  })
  // 10.0 x 5.625 at y=1.2 ends at 6.825, clear of the 6.95 footer. The clip's own opening
  // card already carries the one-line description, so there is no caption under the frame.
  const w = 10.0, h = w * 9 / 16             // the clips are 1600x900
  // Without `cover`, PowerPoint shows pptxgenjs's generic grey play-button placeholder —
  // NOT the video's first frame. Hand it a representative UI frame (make-videos.sh writes
  // one per clip), base64 with a data header — addMedia throws without the header. Using
  // the clip's own title card here instead just duplicates the slide header above it.
  const poster = path.join(HERE, '.video/poster', clip.name + '.png')
  const cover = fs.existsSync(poster)
    ? 'data:image/png;base64,' + fs.readFileSync(poster).toString('base64')
    : undefined
  s.addMedia({ type: 'video', path: file, cover, x: (W - w) / 2, y: 1.2, w, h })
  chrome(s)
  return s
}

// ---- 1. title -----------------------------------------------------------
{
  const s = newSlide()
  s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: 0.28, h: 7.5, fill: { color: NAVY } })
  s.addText('Starting an AI project', {
    x: 1.1, y: 2.1, w: 11, h: 0.95, fontFace: TITLE_FONT, fontSize: 46, color: NAVY,
  })
  s.addText('What the first day should look like, what it costs, and what comes out — worked through one real product',
    { x: 1.1, y: 3.1, w: 10.9, h: 0.8, fontFace: BODY_FONT, fontSize: 18, color: TEXT })
  s.addShape(pptx.ShapeType.rect, { x: 1.12, y: 4.05, w: 1.8, h: 0.045, fill: { color: BLUE } })
  s.addText('The worked example: Process Inspector — a multi-engine operations console for Flowable',
    { x: 1.1, y: 4.35, w: 10.6, h: 0.4, fontFace: BODY_FONT, fontSize: 13.5, color: NAVY })
  s.addText('6 July – 1 September 2026 · workflow platform team · figures counted from the repository on 14 September 2026',
    { x: 1.1, y: 4.78, w: 10.6, h: 0.4, fontFace: BODY_FONT, fontSize: 12, color: MUTED })
}

// ---- 2. the question ----------------------------------------------------
{
  const s = newSlide()
  head(s, 'Most AI projects fail before the first prompt', 'The question')
  bullets(s, [
    { b: 'The failure is not the model.', t: 'It is starting to generate code against a goal nobody has written down, with no way to tell a good answer from a plausible one.' },
    { b: 'An AI project amplifies whatever you give it.', t: 'A vague brief produces a large amount of confident, well-formatted, wrong work — faster than a human team could produce it, and harder to unpick.' },
    { b: 'So the question is not “which model”.', t: 'It is: what has to exist before you let it build, and what has to be true before you let it merge.' },
    { b: 'This deck answers that with one worked example', t: '— a real product, built this way, with the numbers, the videos, the bill, and the things the method refused to ship.' },
  ])
  chrome(s)
}

// ---- 3. what came out ---------------------------------------------------
{
  const s = newSlide()
  head(s, 'One console for every stuck process', 'The worked example')
  bullets(s, [
    { b: 'The job it does:', t: 'an on-call engineer finds, diagnoses and fixes a failed process instance in one place — instead of an SSH session, a database query and a guess.' },
    { b: 'Multi-engine by design:', t: 'every Flowable environment in one search, each labeled with its environment colour — including engines on different major versions.' },
    { b: 'Safe by construction:', t: 'read-only by default; every fix is role-gated, requires a stated reason, and is written to an audit trail the incumbent tool does not have.' },
    { b: 'Zero footprint on the engines:', t: 'integrates strictly through the public Flowable REST API — no database access, no plugin, no agent to install.' },
    { b: 'Status today:', t: 'live on the demo host, released as versioned container images, with a nightly full-system test run. Every video in this deck was recorded against it.' },
  ])
  chrome(s)
}

// ---- 4. numbers ---------------------------------------------------------
{
  const s = newSlide()
  head(s, 'From empty repository to released product in 22 days', 'Outcome')
  const cols = 3, gw = (W - 2 * M - 0.4 * (cols - 1)) / cols
  const data = [
    ['22', 'days to the v1 release', '6 – 27 July 2026'],
    ['143', 'pull requests merged', 'every one green in CI first'],
    ['128k', 'lines of product code', '81k backend · 47k frontend'],
    ['2,450', 'automated tests', 'incl. 276 against real engines'],
    ['119', 'tracked requirements', 'each traced to code and tests'],
    ['21,600', 'lines of living specification', 'kept in step with the code'],
  ]
  data.forEach((d, i) => {
    tile(s, {
      x: M + (i % cols) * (gw + 0.4),
      y: 1.75 + Math.floor(i / cols) * 2.0,
      w: gw, h: 1.72, big: d[0], label: d[1], sub: d[2],
    })
  })
  s.addText('One person directing AI coding agents under a fixed set of engineering rules. The rules are the transferable part.', {
    x: M, y: 6.05, w: W - 2 * M, h: 0.35, fontFace: BODY_FONT, fontSize: 12.5, italic: true, color: MUTED,
  })
  chrome(s)
}

// ---- 5. section: the playbook ------------------------------------------
section('Part one', 'How to start an AI project',
  'Seven steps. The first three happen before any product code exists — that is the whole point.')

// ---- 6. playbook at a glance -------------------------------------------
{
  const s = newSlide()
  head(s, 'The seven steps, and when each one pays', 'The playbook')
  const rows = [
    ['1', 'Research the field before you prototype', 'Day 1'],
    ['2', 'Review the specification like a board, not a document', 'Day 1'],
    ['3', 'Turn every adjective into a number', 'Day 1'],
    ['4', 'Make the specification the system of record', 'Continuous'],
    ['5', 'Cut the work into slices that each ship', 'Continuous'],
    ['6', 'Write the rules the project may never break', 'Day 1, enforced forever'],
    ['7', 'Automate the feedback loop, not just the typing', 'Week 1'],
  ]
  rows.forEach((r, i) => {
    const y = 1.78 + i * 0.72
    s.addShape(pptx.ShapeType.ellipse, { x: M, y: y + 0.04, w: 0.44, h: 0.44, fill: { color: BLUE } })
    s.addText(r[0], {
      x: M, y: y + 0.04, w: 0.44, h: 0.44, align: 'center', valign: 'middle',
      fontFace: BODY_FONT, fontSize: 14, bold: true, color: WHITE,
    })
    s.addText(r[1], {
      x: M + 0.72, y: y, w: 8.5, h: 0.5, valign: 'middle',
      fontFace: BODY_FONT, fontSize: 16, color: NAVY,
    })
    s.addText(r[2], {
      x: M + 9.4, y: y, w: 2.4, h: 0.5, valign: 'middle', align: 'right',
      fontFace: BODY_FONT, fontSize: 12, italic: true, color: MUTED,
    })
    if (i < rows.length - 1) {
      s.addShape(pptx.ShapeType.rect, { x: M + 0.72, y: y + 0.62, w: W - 2 * M - 0.72, h: 0.006, fill: { color: RULE } })
    }
  })
  s.addText('Steps 1–3 cost about one day. Skipping them is the most expensive decision available to you.', {
    x: M, y: 6.55, w: W - 2 * M, h: 0.35, fontFace: BODY_FONT, fontSize: 12, italic: true, color: MUTED,
  })
  chrome(s)
}

// ---- 7. step 1 ----------------------------------------------------------
{
  const s = newSlide()
  head(s, 'Start with a market study, not a prototype', 'Step 1 · research')
  bullets(s, [
    { b: 'Study 1 — the incumbent.', t: 'A full feature and permission inventory of the tool people use today, together with its documented weaknesses: no saved searches, no search by instance ID or variable, manual-only bulk, and no audit trail of administrator actions.' },
    { b: 'Study 2 — the field.', t: 'Seven comparable tools (Camunda, Temporal, Flowable Control, Conductor, Airflow, AWS Step Functions, Azure Durable Functions) reduced to twelve patterns the whole industry has converged on.' },
    { b: 'Study 3 — the platform.', t: 'What the underlying API can and cannot actually do — established by experiment against a real engine, before anything was promised to anyone.' },
    { b: 'This is the step AI is unreasonably good at.', t: 'Breadth of prior art, in hours, with citations you can check. It is also the step teams skip because it produces no running code.' },
    { b: 'Output of day one:', t: 'a specification with a glossary, numbered design principles, and an explicit list of things we would not build.' },
  ], { size: 15, gap: 11 })
  chrome(s)
}

// ---- 8. step 2 ----------------------------------------------------------
{
  const s = newSlide()
  head(s, 'Review the specification like a board', 'Step 2 · adversarial review')
  bullets(s, [
    { b: 'Round 1 — four expert seats.', t: 'Workflow-engine specialist, senior support engineer, lead developer and UX expert each reviewed independently, then the findings were reconciled into one specification.' },
    { b: 'Round 2 — a fourteen-seat review board.', t: 'Product owner, business analyst, architect, DevOps, test manager, usability experts and day-shift, L2 and L3 support staff. Roughly 130 findings.' },
    { b: 'Reconciliation, not averaging.', t: 'Where seats disagreed the conflict was recorded and decided in writing — including two features cut from the first release on the product owner’s argument.' },
    { b: 'Each seat is a role, not a rubber stamp.', t: 'A model asked to review “as the 3am support engineer” finds different defects than the same model asked to review “as the architect”. Naming the seat is what produces the disagreement — and the disagreement is the value.' },
    { b: 'Result:', t: '119 numbered, prioritised requirements and 3 recorded architecture decisions. Every later change refers back to a requirement number.' },
  ], { size: 14.5, gap: 10 })
  chrome(s)
}

// ---- 9. step 3 ----------------------------------------------------------
{
  const s = newSlide()
  head(s, 'What that one day of research paid for', 'Step 3 · the return')
  bullets(s, [
    { b: 'Six correctness traps found before any product code.', t: 'Most serious: the engine returns failed-job lists ten at a time, so a naive implementation lets a broken process instance appear healthy. Every competitor-grade tool has to solve this; we knew it on day one instead of after the first incident.' },
    { b: 'Four expensive dead ends refused up front.', t: 'A query language, an undo-able change preview, live streaming search and engine-internals reporting were all specified away — the platform cannot support them, and each would have cost weeks to discover.' },
    { b: 'Business blind spots caught early.', t: 'The first specification had no success measures, no release gate and no rollout plan. The product owner’s seat added them before build, not after pilot.' },
    { b: '“Adjectives where numbers belong.”', t: 'Every limit, timeout and threshold was rewritten as a testable number — which is precisely why they could later be enforced automatically instead of argued about in review.' },
  ], { size: 15, gap: 12 })
  chrome(s)
}

// ---- 10. step 4 ---------------------------------------------------------
{
  const s = newSlide()
  head(s, 'The specification is the system of record', 'Step 4 · operating model')
  bullets(s, [
    { b: 'Three documents, three questions.', t: 'What the product does · how and why it is built that way · when each piece lands. Nothing lives in someone’s head, because there is no head to keep it in.' },
    { b: 'The lockstep rule.', t: 'A change in behaviour updates the matching document section in the same change. A change that does not is not finished.' },
    { b: 'Traceability.', t: 'One matrix links every requirement to the code that implements it and the test that proves it — which is what makes an audit or a compliance question answerable in minutes.' },
    { b: 'Why this is the load-bearing practice.', t: 'An AI agent has no memory of last week’s decision and no instinct about house style. It reads the rule or it invents one. Written rules are the only way the tenth change looks like the first.' },
  ], { size: 15, gap: 12 })
  chrome(s)
}

// ---- 11. step 5 ---------------------------------------------------------
{
  const s = newSlide()
  head(s, 'Slices that each ship — never a big-bang integration', 'Step 5 · delivery')
  bullets(s, [
    { b: 'Every feature was cut into four to six slices,', t: 'each one independently reviewable, releasable and demonstrable on its own.' },
    { b: '143 changes in eight weeks', t: '— the average change stayed small enough to be reviewed properly rather than waved through.' },
    { b: 'No long-lived branches.', t: 'Work merged the day it was written, so two work streams never spent a week diverging.' },
    { b: 'Evidence:', t: 'the five largest second-phase features — shared team views, deep result paging, runtime engine administration, identity and access, and the incident ledger — each went from design lock to complete within one to two days.' },
    { b: 'The trade we accepted:', t: 'more design decisions up front, in exchange for near-zero integration risk at the end.' },
  ], { size: 15, gap: 11 })
  chrome(s)
}

// ---- 12. step 6 ---------------------------------------------------------
{
  const s = newSlide()
  head(s, 'Guardrails are what make speed safe', 'Step 6 · the rules')
  bullets(s, [
    { b: 'A short list of non-negotiable rules', t: 'covers the ways this product could do real damage: never write to an engine’s database, every corrective action audited and role-gated, database schema only through reviewed migrations.' },
    { b: 'The interface between frontend and backend is generated, not hand-written.', t: 'The two halves are structurally incapable of drifting apart — a whole class of defect simply cannot occur.' },
    { b: 'Formatting and code standards are build failures,', t: 'not review comments. Nobody spends attention on style.' },
    { b: '“Done” means the build is green on the merged commit', t: '— a successful upload is not success. This one definition removed most of the “it works on my machine” traffic.' },
    { b: 'Rules beat instructions.', t: 'An instruction is followed once. A rule that fails the build is followed every time, by everyone, including the contributor who has never read it.' },
  ], { size: 14.5, gap: 10 })
  chrome(s)
}

// ---- 13. step 7 ---------------------------------------------------------
{
  const s = newSlide()
  head(s, 'Automate the feedback loop, not just the typing', 'Step 7 · the loop')
  bullets(s, [
    { b: 'Build and test time: about 25 minutes → 3 minutes 45.', t: 'Achieved by running the suite across six parallel build machines. Fast feedback changes behaviour; slow feedback gets skipped.' },
    { b: 'A realistic environment on demand.', t: 'Real engines start in containers for the tests. Engine behaviour is never faked — which is how the truncation traps stay caught.' },
    { b: 'Usability tested before shipping.', t: 'Scripted incident missions are driven against the real user interface by test agents; a surface ships when the mission success rate clears an agreed bar.' },
    { b: 'Independent review on every design and change.', t: 'Separate AI review models act as a second and third opinion at negligible cost — they caught real defects the author had signed off.' },
    { b: 'This is where the leverage actually is.', t: 'Generating code is the cheap part. Deciding whether the code is right is the expensive part, and it is the part worth automating.' },
  ], { size: 14.5, gap: 10 })
  chrome(s)
}

// ---- 14. timeline -------------------------------------------------------
{
  const s = newSlide()
  head(s, 'How the eight weeks were actually spent', 'Timeline')
  const rows = [
    ['Day 1', 'Research and specification only', 'Three market studies, an expert panel and a 14-seat review board — no product code written.'],
    ['Week 1', 'Core product', 'Engine registry, cross-engine search, instance detail with diagram, corrective actions, audit trail, roles.'],
    ['Week 2', 'v1 close-out and release', 'Bulk operations, hardening, container release pipeline, live demo environment, parallel CI.'],
    ['Week 3', 'Demand-driven v2', 'Shared team views, deep paging, runtime engine administration, identity and access, incident ledger.'],
    ['Week 4', 'Adversarial review and fix', 'Whole-application review; 17 findings implemented and merged; framework versions brought current.'],
    ['Weeks 5–8', 'Research-driven roadmap', 'Literature-grounded features — and the measurement gates that decided which of them could ship. Part three.'],
  ]
  rows.forEach((r, i) => {
    const y = 1.66 + i * 0.85
    s.addShape(pptx.ShapeType.roundRect, { x: M, y, w: 1.65, h: 0.64, rectRadius: 0.08, fill: { color: NAVY } })
    s.addText(r[0], {
      x: M, y, w: 1.65, h: 0.64, align: 'center', valign: 'middle',
      fontFace: BODY_FONT, fontSize: 13, bold: true, color: WHITE,
    })
    s.addText(r[1], {
      x: M + 1.9, y: y - 0.02, w: W - M - 1.9 - M, h: 0.32,
      fontFace: BODY_FONT, fontSize: 14, bold: true, color: NAVY,
    })
    s.addText(r[2], {
      x: M + 1.9, y: y + 0.28, w: W - M - 1.9 - M, h: 0.4,
      fontFace: BODY_FONT, fontSize: 12, color: TEXT,
    })
    if (i < rows.length - 1) {
      s.addShape(pptx.ShapeType.rect, { x: M + 1.9, y: y + 0.74, w: W - 2 * M - 1.9, h: 0.008, fill: { color: RULE } })
    }
  })
  chrome(s)
}

// ---- section: what came out --------------------------------------------
section('Part two', 'What the method produced',
  'Seven recordings from the live demo environment — real engines, real data, real corrective actions.')

// ---- how to read the clips ---------------------------------------------
{
  const s = newSlide()
  head(s, 'About these recordings', 'Part two')
  bullets(s, [
    { b: 'Nothing here is a mock-up.', t: 'Every clip was recorded by driving the deployed application against three live Flowable engines — two on 6.8, one on 7.1 — carrying two months of accumulated demo history.' },
    { b: 'The corrective actions really ran.', t: 'In the “fix” clip a process variable is corrected and the failed step retried, and the instance ends COMPLETED on camera. In the “bulk” clip nine dead-lettered jobs are re-queued and reported individually.' },
    { b: 'Click a video to play it.', t: 'Each one opens on a caption card and runs 20–55 seconds. They are also in presentation/videos/ as ordinary mp4 files for reuse in onboarding or a ticket.' },
    { b: 'Watch for the refusals, not the features.', t: 'Greyed-out buttons that name the missing permission, counts that admit they are lower bounds, a confirm button that restates the change instead of saying “OK”. That is the specification showing through.' },
  ], { size: 15, gap: 12 })
  chrome(s)
}

// ---- the seven product clips -------------------------------------------
CLIPS.filter((c) => c.name !== 'ledger').forEach(videoSlide)

// ---- section: consequences ---------------------------------------------
section('Part three', 'The consequences of asking AI to beat the incumbent',
  'We asked for a better console than the vendor’s own — then we asked the scientific literature what “better” means.')

// ---- what we asked ------------------------------------------------------
{
  const s = newSlide()
  head(s, 'Two research questions, asked deliberately', 'Consequences · the brief')
  bullets(s, [
    { b: 'Question 1 — “beat the incumbent.”', t: 'Given the vendor’s own console and six comparable products, what would a genuinely better operations tool do? Answered from feature inventories and documented weaknesses, not from opinion.' },
    { b: 'Question 2 — “what does the science say?”', t: 'Alarm management, predictive process monitoring and alarm-flood analysis are established research fields. Twenty-five papers were collected and fourteen are cited by DOI in the design documents.' },
    { b: 'Why the second question matters commercially.', t: 'Question 1 can only produce parity plus polish — everyone in the market is reading the same competitors. Question 2 is where a feature comes from that no competitor has.' },
    { b: 'The cost of asking:', t: 'a few days of an agent’s time and about the price of a good lunch in model spend. The cost of not asking is a roadmap that is a copy of somebody else’s.' },
  ], { size: 15, gap: 12 })
  chrome(s)
}

// ---- what competitor research produced ----------------------------------
{
  const s = newSlide()
  head(s, 'What the competitor study changed', 'Consequences · question 1')
  bullets(s, [
    { b: 'Failures are grouped by error class, not listed by instance.', t: 'Every incumbent shows you a list of broken instances. One root cause producing forty of them is one card here, with the per-engine and per-version breakdown inside — and one bulk action that covers the class.' },
    { b: 'The variable ledger is typed, not a JSON blob.', t: 'The vendor console renders process variables as a raw JSON string. That was recorded as a rejected anti-pattern on day one; the replacement is a typed ledger with plain-language type chips and a compare-and-set editor.' },
    { b: 'An audit trail of administrator actions.', t: 'The single largest gap in the incumbent: the engine attributes every corrective action to one shared service account. The Inspector keeps its own append-only log and states plainly that it — not the engine — is the authoritative record of who did what.' },
    { b: 'Honesty markers everywhere.', t: 'Twelve patterns the field has converged on, plus one it has not: no number derived from truncated data is ever shown without a badge saying so.' },
  ], { size: 14, gap: 10 })
  chrome(s)
}

// ---- what scientific research produced ----------------------------------
{
  const s = newSlide()
  head(s, 'What the scientific literature added', 'Consequences · question 2')
  bullets(s, [
    { b: 'A persistent incident ledger with episodes and time-to-resolution.', t: 'A triage dashboard forgets a failure class the moment its queue drains. The ledger keeps one incident per root cause across drain, resolve and regression — so “did it come back?” is answerable, and every episode carries its own duration. That is the MTTR record the tool previously could not produce.' },
    { b: 'A self-heal evidence lane — descriptive statistics, deliberately not machine learning.', t: 'The benchmark literature on predictive process monitoring finds that simple aggregate encodings are a strong baseline and complex models rarely justify themselves. So v1 counts observations; a trained model is explicitly deferred until the simple version is proven insufficient.' },
    { b: 'Stability as a normative rule, not a preference.', t: 'The same authors show that predictions shown to humans must be optimised for stability across refreshes, not accuracy alone. A flapping badge is worse than no badge — so hysteresis and dwell rules are in the specification, not in someone’s judgement.' },
    { b: 'Cost-aware attention ranking.', t: 'From the alarm-flood and alarm-management literature: rank what an operator sees by the cost of ignoring it, not by how many rows it has.' },
  ], { size: 13.5, gap: 9 })
  chrome(s)
}

// ---- the ledger clip ----------------------------------------------------
videoSlide(CLIPS.find((c) => c.name === 'ledger'))

// ---- the uncomfortable half --------------------------------------------
{
  const s = newSlide()
  head(s, 'The same research refused to let three of them ship', 'Consequences · the gates')
  bullets(s, [
    { b: 'Cost-aware attention ranking: built, shipped switched OFF.', t: 'The design carries a five-axis data-maturity gate. At the time of writing, zero of five axes were met — so the feature ships inert and flipping it requires re-running the measurement. Earliest possible date: end of September 2026.' },
    { b: 'The self-heal badge gates itself, per failure class.', t: 'It ships enabled and still refuses to answer. On the live demo it reads “no reliable self-heal history yet — 1 of 10 spells observed”. You can see it in the previous clip. It would rather say nothing than guess.' },
    { b: 'One research track was closed by its own measurement.', t: 'A proposed grouping-quality improvement measured its own benefit at 0.0% on real data, and was closed instead of shipped.' },
    { b: 'The usability A/B run passed — and found the real problem.', t: 'Under count-ordering every tester went to the biggest failure class; under cost-ordering every tester went to the costliest one. But three of five only switched after reading the explanation — reproducing a published finding that the display alone does not change the decision. The reordering shipped with the explanation made visible, not hidden in a tooltip.' },
  ], { size: 13.5, gap: 9 })
  chrome(s)
}

// ---- the lesson ---------------------------------------------------------
{
  const s = newSlide()
  head(s, 'The consequence worth taking away', 'Consequences · the lesson')
  bullets(s, [
    { b: 'Asking AI for features produces features.', t: 'Fast, plausible, competitive, and impossible to distinguish from guesswork once they are in the backlog.' },
    { b: 'Asking AI for evidence produces gates.', t: 'A gate is a written condition, measured against real data, that decides whether the feature is allowed to be on. Three of our research features are currently held behind one.' },
    { b: 'The gates are the deliverable.', t: 'They are what lets you ship an ambitious roadmap without betting the operations console on a hunch — and what lets you say “not yet” with a date attached instead of an argument.' },
    { b: 'It also means saying so out loud.', t: 'Two of the four review seats on the most recent design round were unavailable — one model retired, one quota-blocked. That is recorded in the document as an owed review rather than quietly filled by a substitute grading its own work.' },
  ], { size: 15, gap: 12 })
  chrome(s)
}

// ---- section: cost and lessons -----------------------------------------
section('Part four', 'What it cost, and what we would change',
  'The model bill, measured from this project’s own session logs — and the honest list of what went wrong.')

// ---- cost ---------------------------------------------------------------
{
  const s = newSlide()
  head(s, 'What this would cost you in API spend', 'Cost')
  const cols = 4, gw = (W - 2 * M - 0.35 * (cols - 1)) / cols
  ;[
    ['$343', 'measured model spend', '11 working days, metered'],
    ['494M', 'billed tokens', 'across 1,627 API calls'],
    ['$22.80', 'per merged pull request', 'the useful unit'],
    ['$3–11k', 'estimated, whole project', '143 PRs · 582 commits'],
  ].forEach((d, i) => {
    tile(s, { x: M + i * (gw + 0.35), y: 1.72, w: gw, h: 1.58, big: d[0], label: d[1], sub: d[2] })
  })
  bullets(s, [
    { b: 'Measured, not modelled.', t: 'Every API call this project made is logged with its token counts and its model. The $343 is those logs priced at public list rates — 11 active days between 4 August and 14 September 2026, which includes the sessions that produced this deck.' },
    { b: 'The whole-project figure is a range because the evidence is.', t: 'Scaling the measured cost by merged pull request gives $3,300; scaling it by commit gives $11,000. The July build phase predates the surviving logs, so take the range as an estimate with a measured anchor — not a bill.' },
    { b: 'Caching is most of the economics.', t: '98.9% of the input tokens were served from cache at one-tenth of the input price. The same work without caching would bill about $2,500 rather than $343 — a factor of seven, out of one design decision about keeping the prompt prefix stable.' },
    { b: 'What the number excludes:', t: 'the human time to direct, review and decide; build infrastructure; the demo host. Put the other way round — the entire model bill for this product is roughly four to fourteen developer-days at an $800 blended rate.' },
  ], { y: 3.46, size: 12.5, gap: 8 })
  chrome(s)
}

// ---- what cost us time --------------------------------------------------
{
  const s = newSlide()
  head(s, 'What cost us time — the honest list', 'Lessons')
  bullets(s, [
    { b: 'Documentation drift.', t: 'One sprint shipped seven fixes without updating the plan or closing the ticket. It was found by a human re-reading the document — the process did not catch it.' },
    { b: 'A shared working copy.', t: 'Parallel work streams changed the same checkout underneath each other, producing confusing failures that were not code defects.' },
    { b: 'Collisions with unrelated projects on the same host.', t: 'A network port held by another team’s container looked like an intermittent test failure for a day before it was diagnosed.' },
    { b: 'Review substitution under quota limits.', t: 'When the designated review model was unavailable, one run improvised a different reviewer and effectively graded its own work. No defect resulted, but the control had failed — and a control that fails silently is worse than no control.' },
    { b: 'Implied steps get skipped.', t: 'Automated contributors reliably omitted one formatting check whenever it was not named explicitly in the task. They do not infer the step from context; they do exactly what the brief lists.' },
  ], { size: 14, gap: 9 })
  chrome(s)
}

// ---- improvements -------------------------------------------------------
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

// ---- next ---------------------------------------------------------------
{
  const s = newSlide()
  head(s, 'What transfers — and what we need from you', 'Next')
  bullets(s, [
    { b: 'None of the seven steps is specific to this product.', t: 'Research and panel review before code · numbered requirements · thin slices · rules that fail the build · evidence gates before a flag flips. The same day-one shape works for any AI project you are considering.' },
    { b: 'Highest-leverage practices to reuse elsewhere:', t: 'generated interface contracts, a realistic containerised test environment, parallel build capacity, and a written gate for anything you cannot yet prove.' },
    { b: 'Decisions we need sponsored:', t: 'three engines onboarded for the pilot including one production engine and its signed checklist; activation of the audit-store backup and recovery procedure; and a decision on the three research features currently held behind their gates.' },
    { b: 'The ask:', t: 'name the pilot owner and the three engines, and give us a date for the production onboarding review.' },
  ], { size: 15, gap: 12 })
  s.addShape(pptx.ShapeType.rect, { x: M, y: 5.72, w: W - 2 * M, h: 0.02, fill: { color: RULE } })
  s.addText('Questions', {
    x: M, y: 5.95, w: 6, h: 0.5, fontFace: TITLE_FONT, fontSize: 22, color: NAVY,
  })
  chrome(s)
}

const out = process.argv[2]
await pptx.writeFile({ fileName: out })
console.log('wrote', out, '·', slideNo, 'numbered slides')
