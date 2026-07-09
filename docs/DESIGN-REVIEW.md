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

## Round 3 (v3.0) — the 14-seat review board

Seats: product owner, business analyst, lead developer, architect, DevOps engineer, test
manager, embedded tester, usability expert, two usability testers (junior-L2 and senior-L3
personas, spec-only cognitive walkthroughs), support team lead, day-shift support engineer,
L3, L2. ~130 findings, deduplicated into **REQUIREMENTS-REGISTER.md** (the ID'd, prioritized
register); test governance → **TEST-STRATEGY.md**; inspector operability → **OPERATIONS.md**.

**The four recurring diagnoses:**
1. *"Technically excellent, product-blind"* (PO) — no KPIs, release gate, rollout ramp,
   compliance path, or license audit existed. → §13, read-only mode, ADR-002/003, re-cut §12.
2. *"Adjectives where numbers belong"* (BA) — every cap/latency/threshold was unquantified
   and untestable. → R-NFR-01..07 quantified service levels.
3. *"Protects the engines, silent about itself"* (architect + DevOps + team lead) — the BFF
   is the most credentialed box in the estate with no threat model, telemetry, recovery,
   break-glass, or pager. → OPERATIONS.md.
4. *"Protects the instance from the operator, not the operators as an organization"*
   (team lead + L2) — no role granularity below OPERATOR, no approvals, no training gate,
   no reporting. → RESPONDER role, protected instances, proposal inbox, shift report.

**Conflicts resolved:**
- PO's v1 re-cut (flow surgery out of the v1 gate) **accepted** over the v2.2 scope — the
  dominant incident shape is served by tiers 0–1; change-state moves to v1.1 with a
  data-driven entry criterion. Tester B's demand for v1 action-cURL is met instead by the
  cheap REST Parity Appendix + per-verb REST preview (already spec'd) — principle 5 stays
  honest without holding v1 hostage.
- SSE v1-vs-v1.x contradiction (lead dev) resolved: **no SSE in v1**, polling suffices;
  SSE lands with tracked bulk, with a full lifecycle contract.
- L2's "less power" vs L3's "more escape hatches": both accepted — they target different
  tiers (RESPONDER split + protected instances DOWN; evidence view, parity appendix,
  forensic passthrough UP, all ADMIN-gated and audited).
- Tester A's FAILED/FAILING confusion (blocker): display rename **FAILING → RETRYING
  (n/m, auto)**; internal flag names unchanged.
- BA's finding that "Suspended > 24h" is unimplementable (no suspension timestamp in the
  REST API) produced the curated-view honesty rule (R-SEM-05).

## Cross-seat consensus (adopted wholesale)
Correct the join before adding features · partial-and-labeled beats complete-and-false ·
error-class grouping is the triage centerpiece · shareable URLs are an incident primitive ·
reasons feed the audit trail (the differentiator IBM lacks) · every verb states what it
preserves · refuse-unscoped destructive bulk · grey-never-hide with the reason.

---

# Addendum 2026-07-06 — Variable presentation & editing panel (→ SPEC v3.3, §4a, R-UXQ-13)

**Trigger:** Flowable Control renders process variables as a raw JSON string — rejected as
the primary presentation. **Positive precedent:** IBM Business Process Choreographer's
variable editing arc (form to edit values + source mode + verification step + confirm),
adopted and modernized. **Method:** two-seat panel — web designer + usability expert —
independent deliberations, synthesized below.

## Convergent findings (both seats, adopted)
- **Form-first, never raw-JSON-primary.** The tab is a typed variable *ledger* (plain-
  language type chips, scope grouping with shadowing badges, per-type rendering, lazy
  virtualized tree for json); raw text survives only as source mode, the raw-diff expander,
  and the R-L3-03 downloads. The operator edits a *value*, never a *payload*.
- **One shared surface**: a change-set editor + one verification screen, reused by
  edit-variable (set of one), complete-task-with-data and rerun-with-overrides (sets of
  many). Three editing UIs was rejected.
- **Leaf-level json editing** in form mode (fix ONE field of a 40-field object without
  seeing a brace); structural changes deliberately deferred to source mode — the population
  that restructures JSON is the population comfortable in a source editor.
- **Type-lock by default** with explicit per-session unlock; a type change is its own
  warning line at verify, never smuggled into the value diff (number→string silently
  breaking downstream gateways is the canonical corruption).
- **Verification = generated plain-language sentence + structural path diff**, produced
  from the same request object as the payload (sentence and payload can never disagree);
  collapsed exact-request/cURL expander serves the technical reviewer.
- **CAS conflict framed as protection**: three values + attribution, "start over from
  current value" — deliberately NO overwrite-anyway button.
- **Timezone-honest date editing**: dual readout (wall-clock in the operator's zone + the
  exact UTC ISO-8601 sent); offset-less input rejected.

## Conflicts resolved
- **Boolean widget**: usability seat proposed a labeled toggle; web-design seat objected
  that toggles read as immediate-effect controls while nothing applies before confirm.
  **Segmented True/False control adopted; toggles banned** on this surface.
- **Source-mode scope**: web-design seat restricted source *editing* to json variables
  (a scalar's form widget already IS its exact representation); usability seat wanted a
  technical view everywhere. **Both honored**: json-only source editing; every type gets
  the exact-request expander at verification.
- **Editor dependency**: CodeMirror 6 (+lang-json, lint) adopted for parse-error line/col
  and folding, as a lazy chunk; Monaco rejected (multi-MB, worker plumbing); bare textarea
  rejected (no error location → guesswork on a 40 KiB payload at 3am).

## Usability exit gates (feed the `usability-testing` skill's task scripts)
1. 3am number fix (edit `orderTotal` 0→149.90, then retry): ≥90% unassisted; stored value
   is a *number* in 100% of completions (type corruption = automatic design fail);
   edit→retry follow-on used, not hunted for.
2. Nested-field fix in a 40-field json: 100% of applied edits change exactly one field;
   zero accidental whole-document reformats.
3. Date edit "tomorrow 09:00 local": stored UTC instant correct in 100%; testers can state
   the wall-clock time the engine will act on.
4. Injected CAS conflict: ≥90% recover unassisted; 0 testers answer "was anything
   overwritten?" incorrectly; no rage-resubmits.
5. Serializable variable: ≥90% conclude read-only *by design* and name the next move;
   0% "the tool is broken".
6. Wrong-target trap (two similar business keys): 0 completed edits on the wrong instance —
   and the catching surface must be the identity restatement or verify sentence, not luck.
7. Confirm-blindness probe: after each confirm, ≥90% can state what/where/which environment;
   median dwell <~2 s on warning-bearing confirms is a red flag regardless of task success.
Instrumented: form-vs-source usage split, type-unlock frequency (≈0 expected in tasks 1–3;
any unlock there is a finding), reason-field content quality.

**Doc deltas:** SPEC §4 Variables tab + new §4a + §5 rows + §10 (CodeMirror) + §12 v1 scope;
register R-UXQ-13; ARCH §4 variables row (CAS, full-value-before-edit); IMPLEMENTATION-PLAN
M3/M4; TEST-SCENARIOS TS-DET-04/15, TS-VERB-06, coverage index.

## Addendum (v3.6) — v1.1 flow-surgery backend: wire evidence (2026-07-06)

Probed live on flowable-rest 6.8 while implementing the change-state guardrails:

- **`GET /repository/process-definitions/{id}/model` cannot type gateways.** The endpoint
  returns the Jackson-serialized `BpmnModel`; a `parallelGateway` element is
  field-identical to an `exclusiveGateway` (no type discriminator anywhere in the JSON).
  It IS authoritative for multi-instance detection: every element entry carries an
  explicit `loopCharacteristics`, and MI subprocess roots expose their body via a nested
  `flowElementMap`. **Resolution:** `BpmnStructure` parses BOTH representations of the
  immutable definition — /model JSON for MI scopes (the mandated source), deployed XML
  (`/resourcedata`, definition-level, verified 200) for gateway types, element names and
  the sequence-flow graph — cached in Caffeine per `engineId:definitionId`.
- **Change-state verified on 6.8**: `POST /runtime/process-instances/{id}/change-state`
  with `{"cancelActivityIds":["stepOne"],"startActivityIds":["stepTwo"]}` → 200, token
  observed moved via unfinished historic activities. There is NO dry-run endpoint —
  preview is a BFF simulation and says so in its `simulationNote` (SPEC §5/§11 honesty).
- **Historic variable rows** nest the value as `variable:{name,type,value,scope}` with a
  top-level `taskId`; restart-as-new carries only `scope=global`, non-intrinsic,
  REST-portable types and reports everything else in `skippedVariables`.
- **Parallel-branch warning is a heuristic by design**: backward walk over incoming flows,
  fork-before-join = warn, climbing out of non-MI subprocess scopes. It gates NOTHING
  (warning only), so false positives on exotic graphs are acceptable; the MI block, which
  DOES gate, uses the engine's own loopCharacteristics — not the heuristic.

**Doc deltas:** ARCH §4 three flow-surgery rows; IMPLEMENTATION-PLAN v1.1 backend-landed
status. SPEC §5/§6 already specified the verbs, guardrails and tier — no WHAT change.

## Addendum (v2) — Registry CRUD: runtime engine lifecycle (2026-07-09)

**Trigger:** the "BFF is now stateful" arc completed for user prefs (Saved Views moved
localStorage→DB, v2/M4); engines remained YAML-plus-redeploy. **Method:** five-seat panel —
security architect, lead developer, DevOps/SRE, support-team lead, UX expert — independent
deliberations against the real M1/M4/v2 code, synthesized. **Full record + threat model +
state machine + API/DDL:** `docs/REGISTRY-CRUD.md`.

**Panel findings that shaped the design:**
- *Security architect (load-bearing seat):* the base-URL is the whole SSRF surface; a
  per-request allowlist is insufficient against DNS rebinding → **resolve-then-pin** demanded;
  the egress allowlist must be **deploy config, not a UI field** (an admin who edits their own
  bound has none); trust is **earned by a read-only probe**, never asserted; registry CRUD is
  higher-privilege than any tier-3 verb → audited fail-closed; break-glass must NOT grant it.
- *Lead developer (real seams):* every consumer already re-reads `registry.all()`/`require(id)`
  live — the only stale caches are `FlowableEngineClient`'s per-id RestClients (evict on edit)
  and the lingering R4j named instances (reset on remove); `id` is immutable forever
  (composite-ID key); the net-new code is the SSRF validator + reload plumbing, everything else
  is V6/audit/RBAC precedent.
- *DevOps/SRE:* YAML must never stop booting the system (air-gap/DR) → DB-authoritative *once
  initialized*, YAML one-time seed, `registry.source: config` pin; secrets stay env-refs;
  readiness contract (R-OPS-01) unchanged.
- *Support-team lead:* the guardrails must be legible in the flow (rule-named rejections, not
  "denied"); the DRAFT→PROBED→ACTIVE "Test connection" ramp is confidence, not friction;
  soft-delete so "removed engine `<id>`" honesty survives.
- *UX expert:* own admin route, greyed-never-hidden; lifecycle as a first-class shape+label
  column; secret **ref-name + presence** indicator, never a masked-value pretense.

**Conflicts resolved:** DB-authoritative-once-initialized (over config-first-overlay) —
one unambiguous source, YAML still cold-boots; all registry writes are REGISTRY_ADMIN (over
letting OPERATOR tune caps) — one door, `dlq-scan-cap` is a do-no-harm knob; soft-delete/
tombstone (over hard delete); **REGISTRY_ADMIN is an orthogonal fleet grant, not a ladder
rung** (you cannot scope "add an engine" to an engine that does not exist).

**Sixth seat — adversarial security review (independent LLM pass):** hardened the locked
design (not just prose): the *pin* re-checks at connect but never re-resolves (the original
"cached forever + re-validate at connect" was contradictory); reload runs strictly
`afterCommit` (in-memory could otherwise run ahead of a rolled-back row); the `/external-job-api`
+ `/cmmn-api` **sibling URLs inherit the base pin + policy** (were an unguarded hole); the
denylist is **IPv6-complete**; base-URL is **canonicalized before validation**; the probe can't
be an oracle (validation errors specific, connect errors coarse); R4j instances are **removed**
not reset on tombstone. "Block startup on YAML≠DB" was rejected (fails every deploy once seeded)
in favor of a loud per-engine drift report + admin badge. → REGISTRY-CRUD.md §2 sixth seat, §4, §5, §9.

**Cross-seat consensus:** trust is earned by a read-only probe · the egress allowlist bounds
the admin so it is deploy config · resolve-then-pin (re-check the *pinned* IP at connect, never
re-resolve) · sibling URLs inherit the base pin · reload is strictly post-commit · YAML never
stops booting but drift is never silent · `id` immutable forever · CRUD audited fail-closed like
a tier-3 verb · secrets stay env-refs and ref-absence is a loud pre-enable failure.

**Doc deltas:** new `docs/REGISTRY-CRUD.md` (authoritative); SPEC §4b + §12; ARCH §3/§4/§5;
REQUIREMENTS-REGISTER R-OPS-13 (expanded) + R-OPS-15 + R-SAFE-13; IMPLEMENTATION-PLAN v2
Registry-CRUD block (S1–S5). No code — design locked, unbuilt.
