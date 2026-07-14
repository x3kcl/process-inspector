# 👥 SHARED (TEAM-WIDE) SAVED VIEWS — design + panel (v2)

**Status:** ★ **BUILT 2026-07-09** — S1–S6 all landed, CI-green (the design lock below is retained as
the WHAT/WHY authority, not a still-pending gate). Built directly on panel-review consensus rather
than a fired duplicate-search-string signal (the stated demand-gate trigger, §1, was never
instrumented in code — historical framing only). The WHAT/WHY/HOW/WHEN below drive the
deltas into `SPECIFICATION.md` (§8/§4 Stage 0 + deferred list), `ARCHITECTURE.md` (the "BFF is stateful"
views arc), `IMPLEMENTATION-PLAN.md` (v2 block) and `REQUIREMENTS-REGISTER.md` (**R-SEM-24, R-SAFE-16**
added). Mirrors the doc-per-feature convention of `INSTANCE-MIGRATION.md` / `REGISTRY-CRUD.md` /
`KWAY-PAGING.md`. **§4.6's "layout capture stays OUT" call was RE-OPENED and superseded 2026-07-14 —
see §8** (issue #197): layout capture landed, URL-encoded, zero backend changes, with a
confirm-prompt precedence rule that resolves both of §4.6's original objections.

**Method:** a draft (a `visibility`+`scope` flip bolted onto the existing per-user `saved_view` row,
with `ScopeGrant.covers()` reused for both the publish gate and read-visibility) was put before a
**5-seat panel** — Flowable-REST wire-shape/honesty, security-architect/do-no-harm, operator-UX/product,
test-manager, architect — plus a **Gemini adversarial 6th seat**. The panel **substantially reshaped**
the draft; the "RE-LOCK DECISIONS" (§4) govern any build. **Unanimous ACCEPT-WITH-CHANGES**, no BLOCK —
the feature is sound and cheap, but three load-bearing corrections were required (separate table +
snapshot-publish; two distinct RBAC predicates not one; replay-time dangling-canon honesty).

**Discharges / reserves:** promotes the deferred v2 "shared server-side saved views" line (SPEC §11 /
IMPLEMENTATION-PLAN v2). Adds R-SEM-24 (team-view model + scoped read-visibility + replay-time
resolvability honesty) and R-SAFE-16 (publish/moderation governance — gate, audit, injection). Depends
on the **already-landed** config-event audit primitive (R-AUD-10, `AuditService.recordConfigEvent`) and
**co-signs one small addition to the scope model** (`ScopeGrant.overlaps()`, §4.3) with IdP-Security.

**Not a spike-gated feature.** Unlike Migration/K-way-paging, there is no unknown engine wire-shape here:
a shared view executes **nothing** against any engine — it is a BFF object. The risk is entirely in the
governance/honesty model, which the panel (not a live spike) de-risked. No S0 spike; no dockerized-engine
slice.

---

## 1. The problem & the benefit

Saved views are per-user and private today (v2/M4, PR #12): `saved_view(id, owner, name, search,
created_at)`, `UNIQUE(owner,name)`, every `ViewStoreService` method keyed on `owner =
Authentication#getName()` — a caller only ever reads or mutates their own rows. A view's `search` is a
**canonical URL search string**: the entire Stage-1 filter state (statuses, engineIds, businessKey,
variables, timeframe), replayed through the URL codec. System views (R-SEM-05 relative windows) stay
client-derived, never persisted.

The benefit of the feature is **an operator/admin publishing curated views the whole team (or a
tenant/engine scope) inherits** — "stuck payments in prod", "failed in the last hour". New responders get
the team's canonical entry points instead of rebuilding them; during an incident everyone *working the
engine* drills the same filter. It codifies **runbook starting points as first-class objects**. Cheap now
the table + endpoints exist — the marginal cost is one small governed table, a publish gate, and
replay-time honesty.

**Why v2 and demand-gated:** a private saved view already solves one operator's problem; the team-canon
version earns its governance surface only if operators are *actually* rebuilding each other's filters.
**Build trigger (architect + Gemini):** instrument **duplicate canonical `search` strings across distinct
owners** (a new/low-tenure user re-creating a filter another user already saved privately). Empty signal ⇒
do not build; private views stay the whole feature. Same discipline as K-way-paging's
`perEngine.total > fetched` gate.

---

## 2. The load-bearing walls (what a naïve `visibility` flag ignores)

Three walls the panel surfaced, each verified against the built code:

### W1 — Scope-of-GOVERNANCE ≠ scope-of-CONTENT (Gemini's fatal flaw; security seat #2).
A view's declared publish scope (`scope_engine_id`) is *metadata*; the engines the `search` string
**actually queries** (`engineIds` inside it) are the *content*. Nothing in the draft bound them. A
per-engine OPERATOR on `engine-A` could publish a view *labeled* `scope=engine-A` (passing the gate)
whose `search` targets `orders-prod,billing-prod`. The label becomes a lie; the gate governs nothing real;
and the stored `search` text **leaks business keys / definition names / error signatures** for engines the
viewer holds no grant on. **Fix: the scope is DERIVED from the search string, not authored free-hand, and
publish is REFUSED if the search references engines outside the declared/derived scope.**

### W2 — `covers()` is CONTAINMENT; read-visibility needs OVERLAP (architect F1).
`ScopeGrant.covers(floor, targetEngine, targetTenant)` answers *"does my (maybe-wildcard) grant ⊇ this
concrete target?"* — a **concrete grant cannot cover a wildcard target**. Trace the draft's D5 for a
global-scoped canon (`scope = *,*`) against a per-engine `VIEWER` grant (`engineId=orders-prod`):
`covers(VIEWER,"*","*")` → `"orders-prod" != "*"` → **false**. So reusing `covers()` for the list would
make a global "stuck payments in prod" canon visible **only to global-grant holders** — the exact inverse
of intent. Publishing legitimately *is* containment ("my authority ⊇ the scope I publish into"); reading
is *intersection* ("is there any engine/tenant I can see that this view's scope also touches?"). **Two
opposite quantifiers — two predicates.**

### W3 — a dangling shared view is a **silent all-clear generator** (honesty seat, verified in code).
`SearchService.resolveTargets()` = `registry.all()` **∩** `req.engineIds()`, and `registry.all()` is
**enabled-only**. A shared view is a frozen URL string *inherited* by responders who never saw its
original context — and when its scoped engine is later disabled/soft-tombstoned by Registry CRUD, replay
**silently drops it with no `perEngine` entry and no honesty marker**. Two grades, both currently
invisible:
- **Partial** — some `engineIds` resolve, some don't → results silently miss the dead engine.
- **Total** — *all* declared engines are gone → `targets` is empty → **zero rows read exactly like an
  honest "no failures."** "Stuck payments in prod" returns a clean grid because prod left the registry,
  not because it is healthy. This is incident-blindness in exactly the incident the canon was published to
  accelerate — the class the iron rule ("never render a status derived from truncated data without the
  badge") forbids, with *no author watching the canon rot*. Definition-version pins (`resolveDefinitionIds`
  → empty on a redeployed/renumbered version) and the **6.3 param-drop cliff** on a mixed fleet are the
  same silent-wrong failure on a different axis. **Fix: replay-time resolvability diff + greyed-with-reason
  canon (§4.5).**

---

## 3. Panel discussion (6 seats → synthesis)

### Flowable-REST / honesty seat
- Owns **W3**. `resolveTargets` is enabled-only; a tombstoned-engine or purged-definition scope replays to
  a **silent narrowing** (partial) or a **clean-looking empty** (total) — the latter is a false all-clear.
  Mandatory: a **replay-time scope/definition resolvability diff** that emits first-class
  `perEngine`/lower-bound markers (partial AND the distinct all-dead state), plus **greyed-with-reason**
  dangling canon in the picker, plus surfacing the existing per-engine 6.3 param-drop canaries at replay.
- **D5 is declutter, NOT security — the picker-visibility filter.** It tidies which canon the picker
  offers; it never redacts a shared view's stored query text (a caller can still read the `search` string
  of any canon they can *see*). Result-set safety is a SEPARATE control: **per-caller read scoping landed
  (S2, R-SAFE-17)** — under `inspector.security.scope-reads-enforced` (on by default in the `oidc` profile)
  `SearchController`→`SearchService` intersect the caller's grants at VIEWER floor, so a per-engine VIEWER
  who pastes a raw URL naming another engine gets that engine labeled **"outside your access scope"** on the
  per-engine envelope (an explicitly-named out-of-scope engine is never silently dropped, R-SEM-24 honesty),
  and an implicit "all engines" search narrows silently to the readable set. When enforcement is OFF (the
  default base config, and effectively so on the global-scoped dev ladder) results are **caller-invariant**,
  as before. A published snapshot may name engines the replaying viewer can't read — those surface as labeled
  excluded legs, not a false "resolved/all-clear".
- **Engine-id reuse after tombstone poisons canon** (a re-registered id silently re-points every view at a
  different engine) → flag registry ids non-reusable.
- **VERDICT: ACCEPT-WITH-CHANGES** (add W3's replay-time diff + greying + the D5-is-declutter statement).

### Security-architect / do-no-harm seat
- Owns **W1** and the **canon-hijack** path: `ViewStoreService.saveView` is an **upsert keyed on
  `(owner,name)` that replaces the search in place**. Reusing it for a shared namespace whose unique key
  drops `owner` lets OPERATOR-B silently overwrite OPERATOR-A's canon by re-saving the same `(name,scope)`
  — bypassing moderation authority and audit entirely. Publish must be **create-only**; overwriting an
  existing canon is a *moderation* act (author-or-scope-ADMIN, audited).
- **Q1 (publish floor):** OPERATOR-on-scope via `covers()` is the right axis — reject a dedicated fleet
  grant (fleet grants are reserved for powers that *can't* be scoped to an engine). **But borrow R-SAFE-14's
  wildcard-breadth doctrine:** any wildcard-scope publish (`engineId='*'` OR `tenantId='*'`) is fleet-wide
  governance and escalates to **ADMIN-on-scope**, not merely global OPERATOR.
- **Q3 (audit):** auditing the shared lifecycle is correct and the `visibility=SHARED` boundary is the
  right place to draw the preference→governance line. Moderation of *another's* canon = reason ≥10 +
  **fires the security-alert channel** (detective backstop — a ≤25-user org can't segregate an approver
  class, per R-SAFE-14) but **not four-eyes** (a filter bookmark's blast radius ≠ apex removal).
- **Injection (D8) is under-drawn:** apply R-OPS-08 to name, description **AND the rendered `search`
  string**, and **CSV-formula-escape** every surface that exports another user's authored text.
- **VERDICT: ACCEPT-WITH-CHANGES.**

### Operator-UX / product seat
- **"view == URL" is the load-bearing invariant** — a view carries zero state beyond a canonical URL
  string, so URL primacy means deleting/unpublishing canon **never breaks anyone mid-incident** (their
  open URL still runs). This makes the scary governance cases benign — and is why layout capture (Q6)
  stays OUT (it would break the invariant and impose one operator's ergonomics on the team).
- **Publish is a deliberate SECOND act, off the hot save path.** Save-as-private stays byte-identical
  (one field). Publish lives in the Stage-0 saved-views section, greyed-never-hidden for VIEWER/RESPONDER
  (`MeDto.engineRoles`). **Scope is derived and shown as one sentence** ("Visible to everyone with access
  to billing-prod"), pre-filled from the view's `engineIds` — collapsing the engine/tenant/global dropdown
  matrix into confirm-the-sentence. This is the single biggest UX win and it operationalizes W1.
- **Runbook-as-object: adopt the R-BAU-03 model** — optional description (≤500) + runbook URL turn a
  shared bookmark into real canon; stop short of R-BAU-03's *endorsed verb* (a view executes nothing).
- **Moderation default verb = UNPUBLISH** (reversible: demote back to the author's private namespace),
  reserve hard-delete. Author/scope off the compact chip → tooltip + Stage-0 list; a **non-color "Team"
  badge** (R-UXQ-01). Canon changes surface via the existing **"Recent operations"** tail, never toasts
  (R-UXQ-06). Dangling canon → **badge, no background poller**; the total-dead case rides W3's distinct
  state, not the ambiguous existing empty.
- **VERDICT: ACCEPT-WITH-CHANGES.**

### Architect seat
- Owns **W2** and the **separate-table** reshape. Bolting governance onto `saved_view`/`ViewStoreService`
  breaks the documented "owner-keyed prefs, no rails" invariant and forces every existing pref query to
  become visibility-aware (leak-or-miss on each method) + a risky `DROP CONSTRAINT` ALTER of already-merged
  V6 work. **Recommendation: a distinct `shared_view` table + `SharedViewService` + `/api/team-views`
  surface**, leaving `saved_view`/`ViewStoreService`/V6 pristine — the exact house pattern Registry CRUD
  used (it created `engine_registry` + its own admin service + its own audit, never bolted onto
  `InspectorProperties`). Consequences all favorable: **publish = snapshot-copy** (name+search+derived
  scope), not an in-place flip → editing your private bookmark can't mutate team canon, and it **kills the
  canon-hijack path structurally** (different table, create-only); the governance line becomes the table
  boundary; each table keeps its own clean uniqueness (no partial indexes, no constraint migration).
- **Split the `covers()` uses** (W2): `covers()` for the containment publish gate only; a new
  `overlaps()` predicate for read-visibility. **Derive `scope_tenant_id` from the registry pin**
  (mirroring `hasRoleOn`), never free client input — the literal is a denormalized snapshot of a registry
  fact.
- **Route lifecycle audit through R-AUD-10's `recordConfigEvent`**, not the engine-mutation `audit_entry`
  path (which is `engineId,instanceId`-shaped; a shared view has a *scope*, no instanceId). **Don't cache
  the list** (small indexed DB read; must reflect publish/unpublish immediately across Traefik replicas).
  Dangling canon rides the Registry tombstone precedent (greyed via R-SEM-17 id→name, read-time resolve,
  never cascade-delete).
- **VERDICT: ACCEPT-WITH-CHANGES.**

### Test-manager seat
- **The scoped RBAC matrix is NOT reachable through the dev basic-auth ladder** — `grantsFor` maps a dev
  `ROLE_*` authority to `ScopeGrant.global(role)`, so dev `operator`/`admin` cover *every* scope. The
  fine-grained cases (per-engine OPERATOR can't publish global; ADMIN-on-B can't moderate A; scoped
  read-filter) must land at **rung-1 over the authorizer/resolver with crafted `ScopeGrant` sets**; rung-3
  door tests can only prove VIEWER-denied + global-OPERATOR-allowed (full OIDC-scoped rung-3 waits on the
  IdP work).
- **Audit is same-transaction atomic** (a BFF object, not a non-transactional engine call): `audit +
  write in ONE @Transactional` via `recordConfigEvent` — do **not** bolt on the `beginPending`/`close`
  dance (it would invent an UNKNOWN outcome class that cannot occur here). The load-bearing D6 proof is the
  **negative** test: a PRIVATE save/delete writes **no** `audit_entry` row.
- **Fail-closed test = mocked-audit-throws → 503 + no visibility flip** (same-tx rollback), cheaper and
  more precise than killing Postgres. **Concurrent-publish race → clean 409** (DataIntegrityViolation
  mapped, never a bare 500). Migration/audit ITs are LOCAL-ONLY (not `ci.yml` itClass), as `ViewStoreIT`/
  `FailClosedAuditIT` already are.
- **VERDICT: ACCEPT-WITH-CHANGES.** *(The separate-table decision — adopted from the architect seat —
  removes this seat's partial-index migration hazards entirely: `shared_view` gets a plain
  `UNIQUE(name,scope_engine_id,scope_tenant_id)`, `saved_view`'s `UNIQUE(owner,name)` is untouched, and the
  publish-flip collision case disappears because publish is an INSERT, not an UPDATE-in-place.)*

### Gemini (adversarial 6th seat)
- **Single most fatal flaw = W1** (governance/content scope divergence); reusing `covers()` on metadata
  that is not congruent with the search string's real engine set is a **category error** → derive the
  effective scope from the search string and gate on *that*.
- Ranked next: no **semantic content validation** on publish (a `businessKey=*` / 5-year-timeframe canon
  becomes a team-wide self-inflicted load) — *mitigated* by the existing search do-no-harm caps that a
  replay hits identically, but the doc must say so; **reason should be mandatory** for all shared changes,
  not just moderation (→ RE-LOCK tightens own-publish to *recommended*, moderation to *required*); an
  **author-departure stewardship** gap (→ scope-ADMIN moderation + unpublish-to-private covers it).
- **YAGNI:** versioning/rollback of a shared view — deferred; not justified at ≤25 users.
- **Adopted in full** for W1; partially for the reason/stewardship points (RE-LOCK §4.4/§4.6).

---

## 4. RE-LOCK DECISIONS (govern any build; supersede the draft)

### 4.1 Separate table + snapshot-publish (architect; kills canon-hijack)
A distinct **`shared_view`** table, **`SharedViewService`**, and **`/api/team-views`** surface.
`saved_view` / `ViewStoreService` / V6 stay pristine (their "owner-keyed prefs, no rails" invariant is
untouched). **Publish = snapshot-copy** the private view's `name` + canonical `search` + derived scope
(+ optional description/runbook URL) into `shared_view` — **create-only**. `shared_view` columns:
`id, author, name, search, scope_engine_id NOT NULL DEFAULT '*', scope_tenant_id NOT NULL DEFAULT '*',
description, runbook_url, created_at, updated_at`, `UNIQUE(name, scope_engine_id, scope_tenant_id)` (plain
constraint — a plain additive `CREATE TABLE`, **no** V6 ALTER, **no** partial indexes). Overwriting an
existing `(name,scope)` is a **moderation** act (§4.4), never a blind upsert.

### 4.2 Scope is DERIVED and content-bound (W1; Gemini fatal flaw)
The publish scope defaults to the union of engines the canonical `search` actually queries (its
`engineIds`), rendered as one sentence. Publish is **refused** if the `search` references any engine
outside the declared scope (bind content to governance, server-side from the parsed string — never a
client assertion). `scope_tenant_id` is **derived from the engine's registry pin** (mirroring
`hasRoleOn`), not free client input.

### 4.3 Two RBAC predicates, not one (W2)
- **Publish gate = `covers()` (containment):** `covers(OPERATOR, scope_engine, scope_tenant)` over
  `grantsFor(auth)`. Global scope needs a global OPERATOR grant (`covers()` already enforces this —
  verified). **Any wildcard scope (`*` engine or tenant) escalates the floor to ADMIN-on-scope**
  (R-SAFE-14 wildcard-breadth). No new fleet grant.
- **Read-visibility = new `ScopeGrant.overlaps(floor, scopeEngine, scopeTenant)` (intersection):** a
  shared view is visible iff the caller holds any grant ≥VIEWER overlapping its scope; global-scoped canon
  visible to every authenticated user. `overlaps()` is a small addition to the scope model — **co-signed
  with IdP-Security** (they own `ScopeGrant`). It is **declutter/relevance, explicitly NOT a security
  boundary** — it does not redact a canon's stored query text. Result-set safety is the separate S2 control
  (R-SAFE-17): when `scope-reads-enforced` is on (default under `oidc`), search/triage reads intersect the
  caller's grants at VIEWER, so an out-of-scope engine named in a replayed snapshot is labeled "outside your
  access scope" rather than returning another tenant's rows. With enforcement off, result sets are
  **caller-invariant**, as before.

### 4.4 Governance authority + audited fail-closed lifecycle (R-SAFE-16)
Author edits/unpublishes/deletes their **own** shared view. **Scope-ADMIN** (ADMIN covering the view's
scope) **moderates** any shared view in that scope — **default verb UNPUBLISH** (reversible: demote to the
author's private namespace), hard-delete reserved. A non-author, non-ADMIN OPERATOR may **not** edit
another's canon. Every lifecycle transition (`view-publish` / `view-update` / `view-unpublish` /
`view-delete`) is **audited fail-closed via `recordConfigEvent` (R-AUD-10), in the same `@Transactional`
as the write** — audit-insert failure ⇒ 503 and no visibility change (no `beginPending`/`close`, no
UNKNOWN class). Payload = name + scope + **search-HASH** (never the raw search — it embeds business keys)
+ before/after visibility. **Reason ≥10 REQUIRED for EVERY unpublish — the author's own included**
(usability W2 #3: unpublish yanks a shared entry point from the whole team, so it is a moderation verb
for everyone; the reason is bound to the audit row's reason COLUMN and rendered first-class in the
operations log). For *edits*, the reason stays required only when moderating *another's* canon
(recommended for own publish/update). Moderation of another's canon **also fires the security-alert
channel** — but **not** four-eyes. PRIVATE-view CRUD stays **unaudited** (the boundary is the table).

### 4.5 Dangling-canon honesty = replay-time resolvability diff (W3)
On replay, resolve the view's declared scope against the **live enabled registry** and (for
definition-scoped views) against **deployed definitions**, and emit first-class honesty riding the
existing `perEngine`/lower-bound envelope: (1) a **distinct** "resolves to no live engine/definition"
state — never a bare empty grid that reads as a clean all-clear; (2) a `perEngine` marker per
declared-but-unresolvable engine ("billing-prod: no longer registered — excluded"); (3) **greyed-with-
reason** dangling team views in the picker (R-SEM-17 id→name survives; never cascade-deleted); (4) surface
the existing per-engine 6.3 param-drop canaries at replay. **No background canon-health poller** — this is
a read-time resolve, evaluated when a responder opens the canon.

### 4.6 UX = deliberate second act, URL-invariant preserved
Save-as-private byte-identical (one field). Publish is a separate, greyed-never-hidden affordance in the
Stage-0 saved-views section (`MeDto.engineRoles`), opening a confirm with the **derived-scope sentence** +
optional description (≤500, R-OPS-08) + runbook URL (R-BAU-03 model). Precedence System → **Team** →
Private; **non-color "Team" badge** on the chip, author/scope in tooltip + Stage-0 list only. Canon
changes surface via the existing **Recent operations** tail (R-UXQ-06), never toasts.

~~**Layout capture (R-UXQ-09) stays OUT** — it would break the view==URL invariant.~~
**SUPERSEDED 2026-07-14 — see §8.** Reopened for issue #197: landed URL-encoded (the SAME `search`
string, not a side-channel store), preserving the view==URL invariant literally rather than breaking
it; the "imposes one operator's ergonomics" half of this objection is resolved by a confirm-prompt at
open-time, not by omitting the feature.

### 4.7 Injection (R-OPS-08, extended)
Text-is-data (no HTML), CR/LF-strip, caps: name ≤200, description ≤500 — applied to name, description
**and the rendered `search` string**; **CSV-formula-escape** every export surface carrying another user's
authored text.

### 4.8 Demand-gated; deliberately deferred
Build only on the §1 duplicate-search-string signal. **Deferred (YAGNI at ≤25 users):** shared-view
versioning/rollback; per-caller result scoping (owned by the IdP direction, not this feature); an
endorsed-verb on a view (it executes nothing). No S0 spike, no dockerized-engine slice.

---

## 5. Surface deltas (when built)

- **New table `shared_view`** (§4.1) — plain additive `CREATE TABLE`; the **Flyway version is reserved as
  intent only, not pinned** (docs-only lock, as K-way-paging did): the next free integer after all
  *merged* migrations at build time (V8 is taken on `main`; V9+ contested by unmerged IdP/M4 worktrees —
  whichever merges last takes the higher number). Separate-table keeps it a conflict-free additive CREATE.
- **`ScopeGrant.overlaps(floor, engineId, tenantId)`** (§4.3) — intersection predicate; co-signed with
  IdP-Security.
- **`GET /api/team-views`** (VIEWER floor; `overlaps()`-filtered), **`POST /api/team-views`** (publish —
  `covers()` gate + content-bound scope check + `recordConfigEvent`), **`PUT /api/team-views/{id}`**
  (author-or-scope-ADMIN), **`POST /api/team-views/{id}/unpublish`** (author-or-scope-ADMIN moderation,
  reason≥10 REQUIRED for every caller — W2 #3; + security alert for another's). The reason-free
  `DELETE /api/team-views/{id}` alias is removed (its only purpose was the author's reason-free path,
  which no longer exists). Concurrent-publish DataIntegrityViolation → **409**.
- **DTOs:** a team-view DTO (`id, name, search, scopeEngineId, scopeTenantId, author, description,
  runbookUrl, isTeam, dangling?` + reason) + a publish request; `SearchResponse` replay honesty reuses the
  existing `perEngine`/lower-bound markers plus the distinct all-dead state (§4.5). `gen:api` regen
  (isolated diff, throwaway BFF on a free port per the memory gotcha).
- **Frontend:** publish affordance + derived-scope sentence, Team group + non-color badge + tooltip
  attribution, moderation (unpublish default), dangling greying, precedence ordering.

---

## 6. Slice plan (each CI-green + independently mergeable; house style)

- **S1 — `shared_view` table + entity (R-SEM-24 schema), standalone.** Additive `CREATE TABLE shared_view`
  (next free `Vn` at build time), `SharedView` entity, repository. **Reserve R-SEM-24/R-SAFE-16 in the
  first commit** (win the parallel-session race, per KWAY S1). No endpoint yet ⇒ zero behavior change.
  Tests: rung-4-DB migration/uniqueness IT (LOCAL-ONLY) — plain `UNIQUE(name,scope_engine_id,
  scope_tenant_id)`, NOT-NULL scope defaults, cross-namespace name reuse vs `saved_view`.
- **S2 — Scope resolver + read-visibility filter (rung-1 core).** `ScopeGrant.overlaps()` (co-sign IdP);
  `SharedViewService.listVisible(auth)` = `overlaps(VIEWER, …)` filter; the content-bound derived-scope
  computation from a parsed `search`. Rung-1 authorizer matrix over crafted `ScopeGrant` sets (per-engine
  vs global vs wildcard; the test-manager's cases 3/4/8/9/11/12). SHARED rows seeded in-test — filter
  proven in isolation, no endpoint.
- **S3 — Publish / unpublish / moderate + audited fail-closed lifecycle (R-SAFE-16).** Snapshot-copy
  publish (create-only, `covers()` gate + wildcard→ADMIN escalation + content-bound refusal), moderation
  authority (author-or-scope-ADMIN, unpublish-default), `recordConfigEvent` in one `@Transactional`,
  injection caps, DataIntegrity→409, security-alert on another's moderation. Rung-3 cached door matrix
  (VIEWER-denied, global-OPERATOR-allowed, forged owner/scope ignored, reason<10→400, CSRF) + rung-4-DB
  audit IT (positive rows A1/A2; **negative A3: PRIVATE write = no audit row**; A4 mocked-throws→503+no-flip;
  A5 no raw search in payload).
- **S4 — Replay-time resolvability honesty (R-SEM-24 honesty).** The scope/definition resolvability diff
  in the replay path: distinct all-dead state, `perEngine` marker per unresolvable engine, 6.3-canary
  surfacing. Rung-1 over the resolver (partial vs total-dead vs clean-empty) + the existing zero-state
  vitest.
- **S5 — API surface + `gen:api` (isolated diff).** Team-view DTOs + publish request; regenerate
  `schema.d.ts` on a free throwaway BFF port.
- **S6 — Frontend.** Publish second-act + derived-scope sentence (greyed-never-hidden via
  `MeDto.engineRoles`), Team group + non-color badge + tooltip attribution, moderation (unpublish),
  dangling greying, precedence, Recent-operations surfacing. Vitest (precedence/visibility/greying/badge) +
  Playwright e2e with **URL-predicate** route mocks (never `**/api/**`).

No S0 spike; no rung-4-**engine** slice (a shared view touches no `ACT_*` table).

---

## 7. Docs-lockstep (this change)

- **New `docs/SHARED-VIEWS.md`** (this file) — authoritative design.
- **`SPECIFICATION.md` §8** — the saved-views sentence gains team/shared views (per-user private + curated
  system + **team-published shared, scoped read-visibility as declutter**), pointing here; **§4 Stage 0**
  note that shared canon renders greyed-with-reason when its scope dangles; **§11** promote the deferred
  "shared server-side saved views" list item to point here with its build trigger.
- **`ARCHITECTURE.md`** — extend the "BFF is now stateful (Saved Views)" arc with the `shared_view`
  governance object (separate store, `overlaps()` read-visibility vs `covers()` publish gate, audit via
  `recordConfigEvent`, replay-time resolvability honesty riding the `perEngine` envelope).
- **`IMPLEMENTATION-PLAN.md`** — expand the folded "shared server-side saved views" one-liner into a v2
  sub-section in the house style.
- **`REQUIREMENTS-REGISTER.md`** — add R-SEM-24, R-SAFE-16.
- **`TRACEABILITY-MATRIX.md`** — add rows for both IDs.

Deeper SPEC §8 *behavioral* edits have landed with the build slices (spec-sync) — S1-S6 are all
built; this section's deltas are historical (the pre-build plan), not a still-open TODO.

---

## 8. RE-OPENED: layout capture (issue #197, 2026-07-14) — §4.6's "stays OUT" call revisited

§4.6 above locked "layout capture stays OUT — it would break the view==URL invariant." Issue #197
asked to reopen that, after #104 shipped a real per-user column-visibility store
(`inspector.resultsGridHiddenColumns`, global, not per-view) that saved/shared views don't capture.
Re-run as a fresh 5-seat panel (UX, security, architect, test-manager + Copilot/Gemini adversarial
6th) rather than a unilateral reversal, since the original call was a deliberate, reasoned rejection,
not an oversight.

**Unanimous mechanism verdict — fold layout into the URL, not a new DB column.** Since the BFF treats
`search` as an opaque, size-capped string it never parses (`SaveViewRequest`/`PublishRequest` DTOs),
and `SharedViewService.publish()` already snapshot-copies `search` byte-for-byte, adding a
`cols=<sorted,ids>` query param to the SAME url-codec (`frontend/src/search/urlState.ts`) that already
owns "the entire search state is URL-encoded" costs **zero backend/DB/migration changes** and
preserves the view==URL invariant *literally*, not just narrowed — a deleted/unpublished canon's URL,
if bookmarked, still fully reconstructs both search AND layout. `cols` is deliberately kept OUT of
`urlState.ts`'s `KEYS` array (which drives `hasSearch()`'s Stage-0-vs-Stage-1 routing) — a URL
carrying only `cols` and no real filter is not "a search."

**The adversarial pass found the panel's first-draft precedence rule was a real flaw, not a
nitpick.** The initial rule ("a viewer's own customized column choice always wins over any
view-encoded suggestion, once they've ever customized anything") independently drew the identical
objection from BOTH Copilot and Gemini: since nearly every active user eventually customizes columns,
a shared view's suggested layout would in practice reach almost nobody past onboarding — a feature
that's dead on arrival dressed up as a compromise. **Adopted fix (Gemini's alternative, refined):**
layout is never silently applied OR silently suppressed. Opening any view (private, shared, or
system) whose search string carries a `cols` suggestion that differs from your current effective
columns shows a small dismissible inline prompt — "This view suggests different columns — Use these
/ Keep mine" — resolved once per (search-identity, suggested-layout) pair and remembered
(`frontend/src/lib/viewLayoutDecisions.ts`, a small capped localStorage decision cache, keyed off the
normalized cols-excluded search string). Manually touching the column chooser while a prompt is
pending counts as "keep mine." "Use these" adopts the suggestion as the new persistent global default
(`columnVisibility.ts`) AND is what makes the URL self-describing — not two independent states to
keep in sync.

**No opt-in publish checkbox, no private/shared split.** An earlier draft of this design gave
private views automatic always-apply capture and gated shared views behind a publish-time checkbox
(default off). Simplified away once the confirm-prompt existed: the prompt itself is what protects
against "one operator's ergonomics imposed on the team" (§4.6's second, independent objection,
alongside URL-primacy) — uniformly, for private and shared views alike, without needing a second
opt-in gate. Saving or publishing a view now *always* captures the currently-effective column set;
the safety property lives entirely at open-time, not save-time.

**Column-ID validation moves client-side, by necessity.** The security seat's guardrail ("validate
column IDs against a live allowlist before persist/render") can't be a server-side check under this
mechanism — the BFF never sees `cols` at all. Decoding filters against `ColumnChooser.tsx`'s existing
`HIDEABLE_COLUMNS` allowlist; unknown/stale IDs (a column later renamed or removed) are silently
dropped, never treated as an error — matching this project's fail-soft-on-decode convention elsewhere
in `urlState.ts` (`decodeVariables`'s try/catch).

**Accepted, documented tradeoff:** editing *only* the layout of an already-published shared view (no
filter change) still changes its stored `search` string, and therefore its audit `searchSha256` —
registering as a `view-update` audit event even though the query criteria didn't change. Low-frequency,
non-security-relevant audit noise; not engineered around.

**Scope:** saved/shared/system views only. Recent-searches (`recordRecentSearch`) is explicitly
untouched — a lower-stakes, unnamed, already-URL-driven feature; folding layout into it is unscoped
scope creep for this issue, not attempted here.

Implementation: `frontend/src/search/urlState.ts` (new `encodeHiddenColumns`/`decodeHiddenColumns`,
outside `KEYS`), `frontend/src/lib/columnVisibility.ts` (additive bulk `setHiddenColumns`),
`frontend/src/lib/viewLayoutDecisions.ts` (new, the per-view decision cache),
`frontend/src/search/SearchPage.tsx` (decode+compare+prompt wiring, the display-search string
threaded to `SearchRail`/`ViewChips`). Zero backend files. No new Flyway migration, no `schema.d.ts`
regen.
