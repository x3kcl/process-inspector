# 🔏 AUDIT ATTRIBUTION — who actually did that?

Release-gate onboarding doc (SPEC §13, R-GOV-02). Normative sources: SPEC §2 ("Audit golden
master"), SPEC §9, ARCHITECTURE §6, OPERATIONS §6. Audience: operators, support engineers,
auditors, and anyone reconstructing an incident after the fact.

## The Service-Account Problem

The Inspector's backend (BFF) authenticates to every Flowable engine with a **shared
machine account** — `inspector-svc` (one unique credential *per engine*, but the same
identity for every human using the tool). This is deliberate: engine-side REST authorization
is binary (`access-rest-api` or nothing), so per-human engine accounts would buy no real
permission model, and the BFF's tenant-scoped RBAC is the only permission layer that
actually exists.

The consequence is **binding and cannot be configured away**:

> Flowable's native history tables (`ACT_HI_IDENTITYLINK`, `ACT_HI_DETAIL`,
> `ACT_HI_TASKINST`, and the engine's own logs) record **`inspector-svc`** as the actor for
> *every* task completion, variable edit, retry, suspension, and termination performed
> through this tool — regardless of which human clicked the button.

Querying the Flowable database will **no longer tell you which engineer moved the token.**
If your incident reconstruction habit is "look at `ACT_HI_*`", that habit is now wrong for
anything the Inspector touched.

## The golden master: the BFF audit log

The Inspector writes its own **append-only audit log** in its Postgres database — and this
is not a dashboard nicety. **It is the definitive system of record for human
accountability.** Investigations start here, not in the engine.

Every action — every tier, including tier-0 queue moves — produces a row *before* the
engine call is issued (**fail-closed**: if the audit insert fails, the mutation is refused;
an unaudited action is treated as worse than no action). Each row carries:

| Field | Meaning |
|---|---|
| `actor` | The **human** (their BFF/OIDC identity — the whole point) |
| `ts`, `correlationId` | When; and the ID threaded through every BFF log line and error envelope for that request |
| `engineId`, `tenantId`, `instanceId` | Where — the composite-ID coordinates |
| `action`, `payload` | The verb + its versioned payload (e.g. edit-variable: `{name, scope, oldValue, newValue, valueType}`; a `scope:"local"` step-local edit also carries `executionId` + `activityId`) |
| `reason`, `ticketId` | Why (reason ≥10 chars where required; ticketId linkified) |
| `httpStatus`, `outcome`, `responseSnippet` | What the engine said: `PENDING → ok \| failed \| unknown` |
| `bulkJobId` | For bulk: one row **per item** plus one envelope row |
| `breakGlass`, `approvedBy` | Emergency-access flag and (where configured) the second approver |

Integrity: append-only DB guard trigger, INSERT/SELECT-only role, per-row **hash chain**
for tamper evidence, monthly partitions, 400-day retention with audited purge
(see [DATA-CLASSIFICATION.md](DATA-CLASSIFICATION.md)).

## How to cross-reference an engine mutation back to a human

Scenario: an engine-side trace (Flowable history row, engine log line, or a DBA's query)
shows a mutation by `inspector-svc` at time **T** on process instance **X** of engine
**E**. Find the human:

1. **Fastest path — the instance itself.** Open `/inspect/E/X`, tab **Audit & Notes**. That
   tab is this instance's complete action history: who, what, when, outcome, old values for
   variable edits, reasons. For most "who touched this?" questions you are done in one step.
2. **The global ops log — the `/audit` page.** Filter by engine `E` + instance `X` (or
   leave instance blank and filter a time window around **T**; remember the audit `ts` is
   the BFF dispatch time — allow a few seconds of skew against engine-side timestamps).
   Additional filters: actor, action, ticketId. This is the page to use when the engine
   trace gives you a definition or a time window rather than one instance.
3. **Match the action shape.** The row's `action` + `payload` tell you exactly what was
   sent (`retry-deadletter-job` with job ID, `edit-variable` with old/new values, …), and
   `httpStatus`/`responseSnippet` echo what the engine answered. A row with outcome
   `unknown` means the BFF dispatched but could not verify — the engine-side evidence you
   are holding may be the missing confirmation.
4. **Bulk actions:** the engine will show N rapid-fire mutations from `inspector-svc`. Each
   maps to one per-item audit row sharing a `bulkJobId`; the envelope row carries the
   submitting human, the reason, and the scope enumeration.
5. **Logs:** every audit row's `correlationId` appears on every BFF log line for that
   request — use it to join audit ↔ BFF logs ↔ (via timestamps) engine logs.

Export: the shift report ("my activity, this shift") and the ops log grid give plain-text /
filterable views suitable for pasting into tickets.

## What the audit log can NOT attribute

Be honest about the boundary — these are the gaps a 3 AM investigation must know:

- **Direct engine access bypasses the golden master.** A cURL straight at the engine's REST
  API (or a DBA in the engine DB) leaves no BFF audit row. That is why the RUNBOOK's
  direct-cURL last resort carries the instruction: **hand-log it in the ticket** — you are
  the audit trail at that point.
- **Engine-internal activity is not human activity.** Automatic retries by the async
  executor, timer firings, and process logic show up in engine history under the executor —
  no BFF row exists because no human acted.
- **Reads are audited only where meaningful** — the log is a mutation ledger, not a page-view
  tracker.

## `X-Forwarded-User` — the optional per-engine bonus

Where **we** control the engine deployment (embedded engines behind the ARCHITECTURE §6
fence, e.g. flap), the BFF additionally sends the acting human in an `X-Forwarded-User`
header and an engine-side interceptor writes native attribution into Flowable's own
history. Treat this strictly as a bonus: it is **per-engine, optional, and never relied
upon** — third-party and standalone engines don't have it, and no procedure in this
document may assume it. The golden master remains the BFF audit log everywhere.

## Break-glass actions

Actions taken under the break-glass account (RUNBOOK §break-glass) are still fully audited
— fail-closed applies, a reason is mandatory on **every** verb including tier 0, and each
row carries `breakGlass: true`. They surface first in the shift report. If you see
`inspector-svc` activity during an IdP outage window, filter `/audit` for break-glass rows.
