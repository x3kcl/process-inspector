# 🚀 OPERATOR QUICK-START — Flowable Process Inspector

Release-gate onboarding doc (SPEC §13, R-GOV-02). Ten minutes of reading before your first
shift with the tool. Companion doc: [AUDIT-ATTRIBUTION.md](AUDIT-ATTRIBUTION.md) — read it
before your first *mutation*.

## What this tool is

One web UI over **multiple Flowable engines** (dev/test/prod), talking to them strictly via
their REST APIs. It never touches an engine database. Every instance is addressed by a
**composite ID**: `engineId:processInstanceId` — e.g. `orders-prod:8123`. Paste either the
composite ID, a bare instance/job/execution ID, or a business key into the **omnibox** (top
bar) and the Inspector resolves it across all engines.

One rule colors everything: **do no harm**. The engines you inspect are usually already
struggling when you open this tool. Calls are circuit-broken and bounded; when an engine
sheds load you see a per-engine error envelope ("circuit open"), never a hung page.

## The three stages

### Stage 0 — Triage landing (`/`, the default route)
Answers *"what is broken, how much, where"* with zero keystrokes:

- **Engine health strip** — per engine: environment-colored badge, version, reachability,
  and **job-lane counts** (see below) with two alarms: *oldest executable job age*
  (executor starvation) and *overdue timers*.
- **Failures grouped by error class** — dead-letter and retrying jobs grouped by normalized
  exception signature, with per-engine and per-definition-version counts ("NPE in
  TaxCalculator — 312 · orders-prod · v47: 312, v46: 0"). Each count is its own
  drill-through into a pre-filtered search. Groups can be **acknowledged** (with reason);
  acknowledged groups collapse but auto-resurface if they grow.
- **Status counts, leak views** (*Active > 30 days* etc.), **recent operations** (audit tail).

Counts are cached ~20 s (the "as of" stamp shows the age; Refresh bypasses, rate-limited).
Any number produced under truncation or an engine error carries a **lower-bound badge** —
"≥ 312" means *at least*; trust the badge, not your optimism.

### Stage 1 — Search + results (`/search`)
Filter rail + AG Grid results. Two things to internalize:

- **The whole search state lives in the URL.** Copy the address bar into a ticket or chat —
  the recipient sees exactly your result set. This is the incident handover primitive.
- **Combination rule: AND between categories, OR within one.** The compiled-criteria echo
  below the form shows exactly what will run, and "copy as cURL" gives you the equivalent
  `POST /api/search` call.

Partial results show an amber banner naming the engine and error ("2 of 3 engines ·
billing-prod: timeout"). A partial result set **blocks bulk actions until you explicitly
acknowledge** the exclusion.

### Stage 2 — Instance detail (`/inspect/{engineId}/{id}`)
Full-page, deep-linkable, tab-aware (`?tab=timeline`). Vitals header (status, business key,
and — when failed — the **"why stuck" strip**: exception first line, retries state, failing
activity), read-only BPMN diagram with token/error markers, then lazy tabs:
**Variables · Errors & Jobs · Tasks · Hierarchy · Timeline · Audit & Notes**.

Use **"Copy for ticket"** (composite ID, definition+version, status, exception, failure
time, deep link — one click) instead of hand-assembling ticket text.

## Job lanes — the lane IS the diagnosis

Flowable has no "FAILED" instance state; failure lives in the **job queues**. The Inspector
keeps the four lanes distinct everywhere (health strip, Errors & Jobs tab) because the lane
tells you what is wrong before you read a single stacktrace:

| Lane | What it means | What you do |
|---|---|---|
| **Executable** | Ready to run *right now*; the async executor should pick it up within seconds. | A growing count or an *old* oldest-job age means the executor is starved or stopped — that's an engine-platform problem, not a process problem. Escalate to the engine owner. |
| **Timer** | Waiting for a due date. **Includes retry back-off**: a failing job parks here between attempts (shown as RETRYING, "attempt 2 of 3, next retry 14:35"). | Usually nothing — it's working as designed. *Overdue* timers (past due, not firing) point at the executor again. |
| **Suspended** | The owning instance (or definition) is suspended; jobs are parked with it. | Resolve the suspension question, then Activate the instance — jobs resume with it. |
| **Dead-letter (DLQ)** | Retries **exhausted**. The engine has given up; nothing will ever happen without a human. This is what the FAILED status chip and the whole triage landing are built on. | Diagnose (stacktrace, variables, siblings), fix the cause if it's data, then **Retry** (moves the job back to executable with retries reset). |

Status chips are **flags, not a partition**: a SUSPENDED instance can hold dead-letter jobs
("SUSPENDED · has dead-letter jobs"), and a FAILED instance still counts as ACTIVE in the
totals. "FAILED — in subprocess *chargePayment*" deep-links to the failing **child** — the
retry lives on the child, never the parent. Every status chip offers **"Explain this
status"**: the per-engine-call evidence behind the derivation, re-derived on demand.

## The guard ladder — how mutations are guarded

Your role decides which tiers you can reach: **VIEWER** (read-only) → **RESPONDER**
(tier 0 + notes — the L1/L2 rung) → **OPERATOR** (tiers 1–2) → **ADMIN** (tiers 3–4). Roles
are scoped per engine and tenant. Actions you can't take are **greyed, never hidden**, with
a tooltip naming the exact gate (role, capability, or state) — so you always know whether
the fix is "ask for a grant", "wrong engine version", or "nothing to act on".

| Tier | Verbs (examples) | Guard |
|---|---|---|
| **0** — reversible-ish | Retry dead-letter job, unstick executable job | No modal. Outcome toast with an explicit delta ("Job 8123 moved to executable queue; retries reset to 3") + audit link. Never a bare "success". |
| **1** — data mutation | Edit variable | Diff confirm (old → new, name, scope). Reason **required on prod**. Compare-and-set: if someone changed the value since you loaded it, you get a 409 and a fresh re-render — never a silent overwrite. |
| **2** — flow surgery | Move token, complete task as | Confirm + required reason + the plan as a sentence + raw REST body preview. |
| **3** — destructive, single | Terminate, delete dead-letter job | Full verification modal — see below. |
| **4** — destructive, bulk | Bulk terminate | Wizard: scope enumeration → reason → on prod **type the item count** → async tracked job. **Refuse-unscoped**: no destructive bulk without at least one narrowing filter. |

### What the tier-3 verification modal actually guarantees

1. **The target is restated server-fresh** — the modal re-fetches the instance at open and
   **warns if it changed** since your grid snapshot. You confirm what *is*, not what *was*.
2. **Cascade victims are enumerated** — a terminate lists the call-activity children that
   die with it.
3. **The environment is unmistakable** — the env color band (dev/test/prod) is inside the
   modal, not just on the page behind it.
4. **The typed token is target-specific** — on prod you type the instance's **business key**
   (or job ID for job deletion), never a generic "yes"/"DELETE". You cannot confirm the
   wrong row by muscle memory.
5. **Cancel is the default** — Cancel holds focus and **Enter never submits**.

### Outcomes: ok, failed — and UNKNOWN

A mutation has three outcomes, and the third is the one that matters at 3 AM:

- **Refused pre-flight** — nothing happened (RBAC, guard, read-only engine, audit
  unavailable).
- **Engine rejected** — nothing happened; the engine's own words are quoted.
- **Dispatched — outcome verification failed / timed out** — **the action may have
  happened.** The UI never shows a generic 500 for a dispatched mutation. It is *never
  auto-retried* (retrying a maybe-succeeded terminate is how you get two dead instances).
  Use **"Verify now"**: the BFF re-checks the precondition ("is the job still in the DLQ?")
  and reclassifies with evidence. Unresolved UNKNOWNs stay in the operations drawer and the
  shift report until someone resolves them.

**Protected instances** (badged) refuse all verbs below the configured floor; bulk
operations auto-exclude them (`skipped (protected)`). **Read-only engines** refuse every
mutation ("engine registered read-only") — that's a rollout contract, not a bug.

## Bulk operations in 30 seconds

Select rows → the bulk bar offers the **intersection** of valid actions (cap 200). Every
bulk run is a **persisted tracked job** with per-item results (`ok / failed / skipped /
skipped (protected) / unknown / not_run`) in the operations drawer — it survives navigation,
browser refresh, and BFF restart (see RUNBOOK for INTERRUPTED jobs). If your selection
overlaps a job that is already RUNNING, you must explicitly run-anyway naming the other job
and its owner.

## The one thing to remember about accountability

Everything you do is audited in the Inspector's own database — with **your** name, reason,
and the exact payload. The *engine's* history will claim the service account did it. When
anyone asks "who touched this instance?", the answer lives in the Inspector's `/audit` page,
not in the Flowable tables. Full story: [AUDIT-ATTRIBUTION.md](AUDIT-ATTRIBUTION.md).
