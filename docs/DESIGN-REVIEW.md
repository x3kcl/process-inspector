# 🧭 DESIGN REVIEW — provenance of SPECIFICATION v2.0

Date: 2026-07-06. Method: two research studies + a four-seat expert panel (each seat an
independent deliberation), synthesized into SPECIFICATION v2.0 / ARCHITECTURE v2 /
IMPLEMENTATION-PLAN v2.

## Inputs

1. **IBM BAW 26.0.x Process Inspector study** (ibm.com/docs content API + support/community
   sources): full feature inventory, action catalog, BPMActionPolicy permission model — and
   its documented gaps: no saved searches, no search by instance ID/task name/variable value,
   non-live snapshot, manual-only multi-select bulk, parent-only variable visibility, **no
   audit trail of admin actions**, invisible-button permission confusion, error details
   per-instance only (no aggregation).
2. **Competitor study**: Camunda Operate/Cockpit, Temporal, Flowable Control, Conductor/Orkes,
   Airflow, Step Functions, Durable Functions → 12 shared industry patterns (dual-mode search,
   saved/shareable views, incident-by-error-class, one-mental-model detail view, timeline,
   re-execution vocabulary, explicit definition drift, token surgery, bulk-by-query with
   per-item reports, graduated guards with reasons, worker visibility, raw-data escape hatch).

## Panel findings that shaped v2.0

### Workflow-engine expert (Flowable REST feasibility)
- Six reproducible status-join bugs: DLQ paging truncation (default page size is **10**),
  suspended-set truncation, call-activity child failures invisible on the searched root,
  the retrying-but-not-dead gap (failing jobs park in the **timer** table between attempts),
  non-exclusive statuses (suspended instances keep their dead-letter jobs), async-history
  lag, plus CMMN job pollution (shared job tables, null `processInstanceId`) and absent
  multi-tenancy threading. → SPEC §3, ARCH §2.3.
- Honest capability map: migration **with server-side validate** reachable via REST (~6.5+);
  change-state has **no dry-run** (preview = labeled BFF simulation) and cancels ALL
  executions at an activity (block multi-instance bodies); timer reschedule and
  Temporal-style reset **impossible** via OSS REST. → SPEC §5/§11.
- Flowable-only differentiators adopted: event-subscription visibility + unstick verbs,
  synchronous job execution as a live diagnostic, four distinct job lanes, external-worker
  view (6.8+), oldest-executable-job-age as the executor-starvation signal, deadletter-delete
  orphan warning.

### Senior support engineer (the 3am seat)
- Omnibox ID-paste is the most frequent entry path; failure-time (not start-time) is the
  incident time axis; triage happens by **failure class**, not instance. → SPEC §4/§8.
- Handover: audit must be a *read* surface on the instance (with old values and reasons),
  plus notes and copy-for-ticket. → SPEC §9.
- Trust: snapshot timestamps, lower-bound labeling, bulk as persisted server-side jobs with
  `ok/failed/skipped/unknown` outcomes, per-engine stagger (a mass DLQ retry can self-DDoS
  the executor), acknowledgment gate when bulking over partial results. → SPEC §7.
- Cuts: bulk instance-suspend (replaced by suspend-definition), task reassign out of v1,
  diagram change-state picker deferred in favor of a plain form.

### Lead developer (cost & sequencing, against the real M1/M2 code)
- Confirmed the DLQ truncation in `SearchService` as the cheapest, highest-priority fix.
- Traps rejected: query language (engine can't execute it), modification-preview-with-undo
  (no engine plan API), live SSE search (polling in a trench coat), executor internals via
  REST (impossible). Kept: SSE for bulk progress (BFF is the event source).
- Postgres arrives at M4 with audit (owed anyway) and then carries notes, bulk jobs, saved
  views; dual basic/OIDC auth profile at M4 to avoid re-doing role mapping later; every
  enumeration loop gets a hard cap + refuse-unscoped.
- 80/20 winners: URL state + deep links, error-class grouping (capped), reason fields,
  per-item bulk reports, job-lane counts, "show the REST call" everywhere.

### UX expert (operator experience)
- Replaced the fixed three-pane IDE layout with **three stages**: triage landing (not an
  empty search form — IBM's mistake), search+results with collapsible rail, **full-page
  deep-linkable instance detail**. → SPEC §4.
- Vitals header with the "why stuck" strip (Temporal Pending-Activities transplant); diagram
  pulled from M5 to M3 (a BPMN tool whose troubleshooting view has no BPMN inverts the
  mental model); errors never buried in a tab.
- Guard ladder tiers 0–4; typed tokens must be **target-specific** (business key / count —
  generic "DELETE" becomes muscle memory); modals restate server-fresh state; delta-statement
  outcome toasts + persistent operations drawer defeat invisible-apply; disabled-with-reason
  tooltips (capability vs role vs state); environment-semantic coloring (freeform engine
  colors demoted). → SPEC §6.

## Addendum (v2.2) — tech-stack review (ADR-001)

Two further advisors: a **principal architect** (read both repos, incl. the companion flap
app) and a **Claude expert** (with what stacks does an AI coding agent perform best).

- **Architect**: confirmed Java 21/Spring Boot + React/TS. Deciding argument: the backend's
  hard problems (OIDC/session/RBAC ceremony, guard enforcement, audit/JPA, bounded fan-out)
  are Spring's sweet spot and the team's depth; the frontend's hard problems (AG Grid,
  bpmn-js, URL state, interaction density) are only honestly solvable in a typed SPA.
  Rejected: Thymeleaf/htmx (the UI is SPA-shaped; agents also can't iterate on it visually),
  NestJS runner-up (security-stack DIY glue + pointless rewrite of working M1/M2), Go/
  FastAPI/Kotlin. Pinned: virtual threads (no WebFlux, no preview APIs), RestClient, Flyway,
  OpenAPI→TS generated contract failing CI on diff, TanStack Query, WireMock as the
  load-bearing test layer, Spotless/ESLint as CI hard failures, one multi-stage image.
  Plus the **flap integration recipe** (ARCH §6): flap embeds Flowable 7 with no REST API —
  one starter, one security filter chain, one machine account makes it inspectable.
- **Claude expert**: ranked TS/NestJS first on iteration speed, Java/Spring a close viable
  second — while its own data showed Java yields the *fewest* silent errors and iterations.
  Its objection to Java (slow feedback loop) is mitigated by scoped test commands; its
  frontend verdict (React+TS+Vite+Playwright ≫ server-rendered templates for agent
  iteration) matched the architect's. Adopted from it regardless of backend choice: repo
  CLAUDE.md with scoped build/test commands + version floors, OpenAPI-generated clients as
  drift guardrails, Playwright as the agent's autonomous visual feedback loop, strict
  typing both sides, lint as hard CI failure.
- **Synthesis**: where the two disagreed (backend language), the architect's whole-system
  weighting won — migration cost of zero, Spring Security maturity for the M4 dual-auth
  requirement, flap-team alignment, and the Claude expert's iteration-speed concern
  addressed by its own recommended mitigations. SPECIFICATION §10 is the decided form.

## Cross-seat consensus (adopted wholesale)
Correct the join before adding features · partial-and-labeled beats complete-and-false ·
error-class grouping is the triage centerpiece · shareable URLs are an incident primitive ·
reasons feed the audit trail (the differentiator IBM lacks) · every verb states what it
preserves · refuse-unscoped destructive bulk · grey-never-hide with the reason.
