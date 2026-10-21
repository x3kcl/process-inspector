# Competitive review — Flowable Control deprecation and Flowable Hub 2026.1

Date: 2026-10-21. Tracking issue: [#481](https://github.com/x3kcl/process-inspector/issues/481).
Method: vendor release notes + the Hub/Control user guides (read 2026-10-21), the Inspector's
own spec/product guide, and a **live feasibility measurement** of every candidate gap feature
against the OSS REST API — read from the running engines' own Swagger documents
(`flowable/flowable-rest:6.8.0` and `:7.1.0`, `/flowable-rest/docs/specfile/*/flowable-swagger-*.json`),
never from memory. Prior competitor study: [DESIGN-REVIEW.md](../DESIGN-REVIEW.md) §Inputs.

## 1. What happened upstream

- **Flowable 2026.1** (initial 2026-07-16; patches up to 2026.1.5 on 2026-10-15) **deprecates
  Flowable Control**. Control still ships in 2026.1, is removed from 2026.2 onward, receives
  limited maintenance (own-code and upstream-fixable third-party defects) until **2027-12-31**,
  and is unsupported after that. Stated reason: Control's UI frameworks reached end-of-life and
  no longer receive security updates.
- The successor is **Flowable Hub**: the React-based administration and monitoring console
  (same frontend stack as Design and Work), first version 2026.1, usable on-premise since
  2026.1, "functionality on par with Control", focus shifted from engines to **apps,
  definitions and instances**. Hub needs its own license entitlement and is configured for
  Flowable Work / Engage / Platform runtimes (cluster toggles: multi-tenant, Work, Engage,
  Elasticsearch, metric indices). Housekeeping and ad-hoc enablement are stated as Work-only.
- 2026.1 patch stream relevant to operations: 2026.1.3 added external-worker jobs to Hub's job
  filtering across process/case/agent engines; 2026.1.4 self-service password management;
  2026.1.5 variable-handling fixes in REST, Hub and indexing.
- **Open source**: unchanged since 7.0 — the bundled UI apps (Modeler, Task, Admin, IDM) were
  dropped; the 6.x Admin app is unsupported and its starters were never published for 7.x.
  There is **no vendor operations console for OSS 7.x fleets**. Hub does not target them.

## 2. What Hub 2026.1 offers (operations-relevant inventory)

| Area | Hub 2026.1 (incl. Control 2026.1 carry-overs) |
|---|---|
| Navigation | Environment (cluster) selector; global search with ⌘K/Ctrl+K; breadcrumb on every detail view; three-level URL scheme |
| Apps / Definitions / Deployments | App upload (.zip/.bar), versions, workspace/revision keys; definitions list (latest toggle, tenant scope), detail with diagram, version selector, Instances / Subscriptions / Jobs / Model Hierarchy / Model Content tabs; deployments by 13 artefact types; delete deployment (with "instances will no longer resolve their model" warning); delete all app versions |
| Work instances | List (status, type, business key, root/parent scope, date ranges); detail tabs Details (owner/assignee), Variables (local toggle, editable), Tasks, Sub Processes/Cases, Jobs, Events, Decisions, Executions, Activities/Plan Items, Form Instances, Actions, Content, Model Hierarchy, Identity Links (manageable since 2026.1), Agent Timeline; actions Suspend/Activate, Terminate (delete reason), Delete, Migrate (incl. historic), Reindex; **process token move** on the diagram; case plan-item actions (activate / move to available / terminate / add-remove wait-for-repetition) |
| Migration | **Assisted migration**: target version → scope (instance / filtered cohort by business key, start range, variables, name / parent set) → pre-flight count → background analysis (compatible/incompatible per activity) → review → execute as batch with live progress → analysis history. **Hierarchy-aware, children first then parent**, circular references skipped. Manual editor: side-by-side diagrams, click-to-map, fan-out/fan-in, table mode, expert JSON with reset-to-generated, user-task overrides for children. Batches view: parts, success/fail parts, batch document |
| Tasks | List with status/assignee/owner/due/created filters; Update (name, description, assignee, owner, due date, priority, category), Assign/claim (empty = unassign), Delegate, Resolve, Complete, Delete, Reindex; sub tasks, identity links, content |
| Jobs | Executable / Timer / Suspended / Deadletter / History / Housekeeping; filters definition (+latest), instance, element, has-exception, due range; lock owner/expiration, full stack trace; Execute, Move (deadletter→executable = retry), **Reschedule** timer, Delete any job |
| Incidents | Threshold crossings (slow async/indexing job, queue full, slow REST/script) — read-only history that clears itself |
| Health dashboard | Instances started (root/sub, process/case) vs reference window; avg jobs/min, timers/min, failures/min; deadletter + incident counts; **pending jobs + pending history jobs**; per-node executor state + queue history; system-status banner with thresholds and card highlighting; live refresh |
| Dashboards | Running/completed instances, system resources (CPU/memory/DB connections), engine operations, async executors, job execution/failures (incl. rejected jobs), event registry, service registry, REST requests, agents + agent cost, indexing, active users; time ranges hour…year + custom |
| System info | Per-node task/async executor cards (threads, pool, queue, locks; editable), job charts, environment (versions, datasource, Java memory, **table row counts**, logging), raw JSON; license compliance dashboard |
| Housekeeping | Wizard: scope process/case; filters definition, business key, start/finish ranges; live preview; at-least-one-filter + non-empty preview; run name, batch size (100), sequential; compute + delete phases as background batches; run list with Stop |
| Security / IDM | Fine-grained `hub:*` permissions; cluster-type roles (e.g. Cluster Reader); IDM users/groups/user definitions; cluster auth types Basic, Bearer, Custom Header, Current User, OAuth2, OAuth2 Current User, OIDC ID token; audit log table (`FLW_HUB_AUDIT_LOG`, not documented in detail) |
| Agents (N/A for OSS) | Agent instances, invocation timeline (PDF), evaluations, guardrail violations, token/cost dashboards, model prices |

## 3. Parity matrix

Legend — **Lead**: the Inspector does it better or Hub lacks it. **Par**: equivalent. **Gap**:
Hub has it, we do not. **N/A**: not applicable to OSS engines. "REST" = measured on the live
6.8.0 and 7.1.0 engines (section 5).

| Capability | Hub | Inspector today | Verdict | REST | Epic |
|---|---|---|---|---|---|
| Fleet view across engines/majors, mixed 6.x/7.x | one environment at a time | multi-engine fan-out, capability gating per engine | **Lead** | — | — |
| Status model (FAILED/RETRYING join, truncation badges) | engine states only | corrected join, `≥N` honesty markers | **Lead** | — | — |
| Triage by error class, acknowledge, attention ordering | — | Stage 0 centrepiece | **Lead** | — | — |
| Incident ledger (episodes, MTTR, regression gate) | threshold incidents (read-only, auto-clear) | persisted failure-class lifecycle | **Lead** (different concept; Hub's needs engine-side metrics) | — | — |
| Audit with human attribution, reasons, tickets, shift report, CSV | hub audit log table | append-only store, fail-closed, break-glass badges | **Lead** | — | — |
| Guard ladder (tiers, typed tokens, reversibility, cURL parity) | confirmation prompts | SPEC §5/§6 | **Lead** | — | — |
| Bulk with per-item outcomes, UNKNOWN + verify-now, circuit pause | batches with success/fail parts | SPEC §7 | **Lead** | — | — |
| Sibling diff, explain-status, copy-for-ticket, deep links | — | built | **Lead** | — | — |
| RBAC scoped per engine/tenant, four-eyes, protected instances, read-only engines | fine-grained hub:* permissions, cluster roles | per-verb grants + fleet grants | **Par** | — | — |
| Omnibox / global search | ⌘K | header omnibox, no shortcut | Par (shortcut missing) | — | #491 |
| Breadcrumbs | yes | no | Gap (small) | — | #491 |
| Instance detail: variables, jobs, tasks, hierarchy, timeline | yes | yes (typed ledger, lanes, compare) | **Par/Lead** | — | — |
| Instance detail: identity links, executions tree, decisions, delete reason | yes | no | Gap | both majors | #487 |
| Change state / move token, rerun, restart | token move | change-state + rerun + restart with BFF simulation | **Par/Lead** | — | — |
| Single-instance migration + pre-check | assisted | honest BFF estimate, CAS execute | Par | both | — |
| Cohort / batch migration, hierarchy-aware, mapping editor | yes | no (deferred by design) | **Gap (largest)** | both (CMMN batch 7.x) | #482 |
| Definitions catalog, version diff, per-version counts | yes (no diff in Hub itself; Design has revision compare) | on-ramp page only | Gap | both | #483 |
| Deployments browser, delete deployment | yes | no | Gap | both | #483 |
| App/deployment upload | yes | no (SPEC §11 non-goal) | Decision | both | #505 |
| Suspend/activate definition (scheduled, cascade) | yes | verbs without options | Gap (options) | both | #506 |
| Jobs: retry, execute now, trigger timer, delete deadletter, external-worker lane | yes | yes | Par | — | — |
| Jobs: timer reschedule | yes | no ("impossible" per SPEC §11) | Gap — **7.x only** | 7.1 yes / 6.8 no | #512 |
| Jobs: history-job lane, moveToHistoryJob, delete any job, global jobs browser | yes | no | Gap | both | #484/#485 |
| Tasks: complete, reassign, return-to-team, person search | yes | yes | Par | — | — |
| Tasks: claim/delegate/resolve, update properties, identity links, delete, browser | yes | no | Gap | both | #486 |
| Health: instances started/completed, throughput, pending history jobs, advisor banner | yes (engine-stored metrics) | lane counts + 2 starvation alarms + sparklines | Gap (REST-honest subset) | history counts both; executor internals **not over REST** | #484 |
| System info: version, properties, table counts | yes | probe only | Gap | both | #509 |
| Executor thread pools, queue, rejected jobs | yes | no | Gap — only via optional actuator scrape | not on engine REST | #510 |
| Housekeeping (history deletion) | yes (Work) | audit-store retention only | Gap | both (id-list bulk) | #488 |
| CMMN: case detail, dead-letter verbs | yes | yes (6.8+) | Par | — | — |
| CMMN: plan-item actions, terminate/delete case, case variables, case migration | yes | no | Gap | both (some 7.x) | #489/#500 |
| Engine/cluster auth: OAuth2, custom header | yes | basic / bearer / none | Gap | — | #490 |
| Current-user passthrough auth to the engine | yes | no, by design (service identity + authoritative audit) | Deliberate | — | — |
| IDM user/group management | yes | no (IdP-backed) | Deliberate; read-only lookup only | idm-api | #522 |
| Elasticsearch indexing / reindex, license dashboard, forms/content/master data/platform | yes | — | N/A (enterprise platform) | — | — |
| Agent instances, evaluators, guardrails, cost | yes | — | N/A (no agent engine in OSS) | — | #492 (MCP exposure only) |

## 4. Where the Inspector leads — keep investing

Hub is an administration console; the Inspector is an incident console. Nothing in Hub 2026.1
answers "what is broken, how much, where, and what did the last shift try": error-class
triage, the incident ledger, honesty markers on every number, the reason-carrying audit log
with shift hand-over, the guard ladder with typed tokens and reversibility badges, bulk with
per-item truth, sibling diff, the mixed-major fleet view. These are the reasons an OSS or
Orchestrate customer — and an enterprise customer running mixed fleets — would run the
Inspector next to or instead of Hub. The catch-up epics below must not dilute them: every
new verb goes through the same rails, every new count carries the same badges.

## 5. REST feasibility evidence (live Swagger, 2026-10-21)

Source of truth: `GET /flowable-rest/docs/specfile/process/flowable-swagger-process.json` (and
`cmmn/…-cmmn.json`, `dmn/…-decision.json`, `external-worker/…-external-worker.json`) on the
demo engines `engine-a` (6.8.0) and `engine-7` (7.1.0). Only existence and request/response
shapes were read; no mutation was issued.

| Endpoint (OSS REST) | 6.8.0 | 7.1.0 | Used by |
|---|---|---|---|
| `POST repository/process-definitions/{id}/migrate` / `…/batch-migrate` | yes | yes | #482 |
| `GET/DELETE management/batches/{id}`, `GET …/batch-parts`, `GET management/batch-parts/{id}(/batch-part-document)` | yes | yes | #482, #493 |
| `POST cmmn-runtime/case-instances/{id}/migrate` | yes | yes | #500 |
| `cmmn-repository/case-definitions/{id}/migrate`, `batch-migrate`, `migrate-historic-instances`, `batch-migrate-historic-instances` | **no** | yes | #500 |
| `GET repository/deployments`, `…/{id}/resources`, `DELETE …/{id}?cascade=`, `POST repository/deployments` (multipart) | yes | yes | #483, #504, #505 |
| `PUT repository/process-definitions/{id}` `{action: activate\|suspend, includeProcessInstances, date}` | yes | yes | #506 |
| `GET runtime/event-subscriptions` (+ `{id}`) | yes | yes | #502 |
| `POST management/timer-jobs/{id}` body `TimerJobActionRequest {action, dueDate}` — "move or reschedule" | **no** (`RestActionRequest {action}`, move only) | yes | #512 |
| `POST management/deadletter-jobs/{id}` actions `move`, `moveToHistoryJob`; bulk `POST management/deadletter-jobs {action, jobIds[]}` | yes | yes | #513, #516 |
| `GET management/history-jobs` (+ `{id}`, exception-stacktrace, `POST` execute, `DELETE`) | yes | yes (adds lockOwner/lockExpirationTime) | #507 |
| `DELETE management/jobs\|timer-jobs\|suspended-jobs/{id}` | yes | yes | #514 |
| `GET management/engine`, `management/properties`, `management/engine-properties`, `management/tables`, `management/tables/{name}` | yes | yes | #509 |
| `GET/POST runtime/tasks/{id}` actions `complete\|claim\|delegate\|resolve`; `PUT runtime/tasks/{id}` (`TaskRequest` with `*Set` flags); `DELETE runtime/tasks/{id}`; task identitylinks CRUD | yes | yes | #486 |
| `GET/POST runtime/process-instances/{id}/identitylinks`, `DELETE …/users/{id}/{type}` | yes | yes | #523 |
| `GET runtime/executions?processInstanceId=`, `GET runtime/executions/{id}/activities` | yes | yes | #524 |
| `GET dmn-history/historic-decision-executions` (+ `{id}/auditdata`), `GET repository/process-definitions/{id}/decision-tables` | yes | yes | #525 |
| `DELETE runtime/process-instances/{id}?deleteReason=`; `POST runtime/process-instances/delete {instanceIds, deleteReason}` | yes | yes | #526 |
| `POST history/historic-process-instances/delete {action, instanceIds[], deleteReason}`; `DELETE history/historic-process-instances/{id}`; `DELETE cmmn-history/historic-case-instances/{id}` | yes | yes | #488 |
| `POST cmmn-runtime/case-instances/{id}/change-state` (`ChangePlanItemStateRequest`) | activate / moveToAvailable / terminate | + add/remove wait-for-repetition, `changePlanItemIds` | #489 |
| `PUT cmmn-runtime/plan-item-instances/{id}` `{action}`; `DELETE cmmn-runtime/case-instances/{id}` (terminate), `…/{id}/delete`; case variables CRUD | yes | yes | #489 |
| Executor thread pools / queue depth / rejected-job counters | **not on the engine REST API** (Micrometer/Prometheus only if the app enables `/actuator/prometheus`) | same | #510 |
| `POST /acquire/jobs/{id}/...`, `GET /jobs` (external worker API); `unacquire` | yes / no unacquire | yes + `unacquire` | existing lane |

Two consequences for the existing docs: SPEC §11's "no timer reschedule — the REST API cannot
honestly provide it" is **true for 6.x and false for 7.x** (fix with #512); SPEC §11's "no
async-executor thread internals" stays true for the engine REST API, with the optional
labelled scrape (#510) as the only exception.

## 6. Verdict — can we compete?

**Yes, in our segment.** Hub is enterprise-licensed and built for Flowable Work/Engage; it
does not serve OSS or mixed fleets, where the Inspector is the only REST-only console since
7.0 removed the Admin app. Against Hub feature-for-feature the Inspector **leads on the
incident loop** (section 4) and **trails on lifecycle administration and engine-internals
observability** (section 3). Every trailing item except executor internals and 6.x timer
reschedule is reachable over the OSS REST API on both supported majors — so the catch-up is
a build problem, not a platform limit.

Positioning sentence: *the operations console for Flowable engines you run yourself — incident
triage first, with the administration surfaces Control used to give you, strictly over REST.*

## 7. Catch-up plan — epics and issues

Tracking: [#481](https://github.com/x3kcl/process-inspector/issues/481). Fifty-six issues:
one tracking issue, ten epics, one decision, forty-four slices. Each epic starts with its
spike/decision slice; each mutating slice names its tier and rails; each count names its
honesty marker. Priorities:

| Prio | Epic | Slices |
|---|---|---|
| P1 | [#482](https://github.com/x3kcl/process-inspector/issues/482) Migration at scale | #493 spike · #494 cohort + pre-flight count · #495 cohort analysis · #496 cohort execution · #497 hierarchy-aware · #498 mapping editor · #500 CMMN migration |
| P1 | [#483](https://github.com/x3kcl/process-inspector/issues/483) Definition & deployment catalog | #501 catalog route · #502 definition detail · #503 version diff · #504 deployments + delete verb · #505 decision: upload · #506 scheduled suspend/activate |
| P2 | [#484](https://github.com/x3kcl/process-inspector/issues/484) Engine health & throughput | #507 history-jobs lane · #508 throughput tiles · #509 System Info · #510 optional metrics scrape · #511 health advisor |
| P2 | [#485](https://github.com/x3kcl/process-inspector/issues/485) Job administration | #512 timer reschedule (7.x) · #513 moveToHistoryJob · #514 delete any job · #515 jobs explorer · #516 spike: bulk DLQ move |
| P2 | [#486](https://github.com/x3kcl/process-inspector/issues/486) Task administration | #517 claim/delegate/resolve · #518 update properties · #519 identity links · #520 delete task · #521 task browser + bulk · #522 idm-api lookup |
| P2 | [#487](https://github.com/x3kcl/process-inspector/issues/487) Instance detail slivers | #523 identity links · #524 executions tree · #525 decisions · #526 delete reason |
| P2 | [#488](https://github.com/x3kcl/process-inspector/issues/488) History housekeeping | #527 spike · #528 tier-4 door · #529 tracked runs + Stop |
| P2 | [#489](https://github.com/x3kcl/process-inspector/issues/489) CMMN case operations | #530 spike · #531 plan-item verbs · #532 terminate/delete case · #533 case variables |
| P3 | [#490](https://github.com/x3kcl/process-inspector/issues/490) Engine connection auth | #534 OAuth2 client-credentials · #535 custom header |
| P3 | [#491](https://github.com/x3kcl/process-inspector/issues/491) Console ergonomics | #536 ⌘K + cheat sheet · #537 breadcrumbs |
| P3 | [#492](https://github.com/x3kcl/process-inspector/issues/492) Decision: read-first MCP server | design + go/no-go only |

Conventions follow the same-day maturity review (#438–#480): campaign label
`hub-parity-2026-10`, `epic` on epics, one `model:*` implementation label per slice
(`model:fable-5.1` wire-shape/capability gating and correctness-critical diffs,
`model:opus-5.5` multi-file engineering with a test net, `model:sonnet-5.5` config/docs/small
option extensions). The maturity set is platform scope, this set is product scope; the
maturity P0s (#443, #449) and the Boot 4 spike (#439) should land before the P1 epics here
start, and #459 (7.2/8.0 harness legs) precedes the new capability gates.

Sequencing: P1 first (both close the "after the fix" gap: migrate the cohort, know what is
live); P2 in the listed order (health and jobs serve the on-call loop directly; tasks, detail
slivers, housekeeping and CMMN are administration parity); P3 when convenient. One epic in
flight alongside pilot support, as in v1.x. The SPEC §11 correction (#512) is a docs change
that can land with any slice.

## 8. Deliberately not pursued

- **Agent observability, evaluators, guardrail dashboards, model prices** — no agent engine in
  the OSS runtime; nothing to observe. Only the MCP-exposure idea is kept as a decision (#492).
- **IDM management (users, groups, user definitions, passwords)** — the Inspector is IdP-backed
  and must never become a user store; only a read-only existence lookup is planned (#522).
- **Current-user passthrough to the engine** — the engine-side identity stays the service
  account; the Inspector log is the authoritative WHO (AUDIT-ATTRIBUTION.md).
- **Elasticsearch indexing/reindex, license compliance dashboard, forms/content/master
  data/platform sections, SLA audit** — enterprise platform features without an OSS
  counterpart.
- **App/deployment upload** — stays a SPEC §11 non-goal unless the PO decision in #505 scopes a
  narrow "promote a known-good version" verb.
- **Hub's threshold incidents** — they need engine-stored metrics; the Inspector's equivalent
  is the executor-starvation alarms plus the health advisor (#511).

## 9. Sources

- Release notes 2026.1.x: https://documentation.flowable.com/latest/admin/release-notes/2026.1.0-release
- What's new 2026.1: https://release.flowable.com/2026.1/ — Hub on-premise and operations
  (`features/hub-onprem-operations/`), assisted case and process migration
  (`features/case-process-migration/`), agent observability (`features/agent-observability/`)
- Hub user guide: https://documentation.flowable.com/latest/user/hub/introduction —
  navigating-hub, apps, definitions, work-instances, migration, tasks, agent-instances,
  maintenance/{deployments,jobs,incidents,batches,housekeeping}, health-dashboard, dashboards,
  system-info, idm/users, hub-configuration/clusters
- Control user guide (deprecated): https://documentation.flowable.com/latest/user/control/ —
  configuration, dashboards, processes, cases
- Monitoring (metrics families): https://documentation.flowable.com/latest/admin/maintenance/monitoring
- Hub licensing: https://documentation.flowable.com/latest/admin/installs/hub-quick; FlowFest
  Q&A on Work-only Hub features: https://www.flowable.com/blog/events/flowfest-q-and-a
- OSS 7.x has no admin UI: https://forum.flowable.org/t/is-there-an-open-source-replacement-for-the-flowable-admin-ui-application/10888
- OSS REST shapes: the running engines' Swagger documents (see section 5), 2026-10-21
