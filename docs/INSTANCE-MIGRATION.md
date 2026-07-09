# Instance Migration — design (v0.3, P0-spiked + panel-RE-LOCKED, ready for P1)

> Status: **P0 spike DONE + panel RE-LOCK DONE (2026-07-09) — ready for P1** (see the ✅ callout +
> "P0 RE-LOCK DECISIONS" below). Was: **design draft**, reviewed by a 4-voice expert panel (Flowable-REST honesty,
> corrective-actions safety, operator-UX/product, and Gemini 2.5). Not yet merged into the
> lockstep docs. When the build is authorized this becomes the authoritative source for the
> migration feature and the SPEC §5 / ARCH §5 / IMPLEMENTATION-PLAN §474-480 edits land in the
> same change (spec-sync). Mirrors the doc-per-feature convention of `REGISTRY-CRUD.md` /
> `CMMN-SCOPE-PHASE-0.md`.

Feature: **migrate a running process instance to another deployed version of the SAME process
definition key** (SPEC §5 tier-3 "Migrate instance"). Move instances wedged on a bad-deploy
version *forward* without terminate+restart — which severs token position and history continuity.

---

## ✅ P0 SPIKE + PANEL RE-LOCK (2026-07-09) — decided, ready for P1
The P0 wire-shape spike ran live against **6.3.1 (engine-legacy :8084), 6.8.0 (engine-a :8081),
7.1.0 (engine-7 :8083)**, deploying a two-version `demoMigration` fixture and cross-checking every
finding against the extracted `flowable-rest` / `flowable-engine` bytecode (not curl alone). It
**invalidated the feature's marquee premise**; a 5-seat panel (Flowable-REST honesty,
corrective-actions safety, operator-UX/product, Gemini adversarial, + adversarial closer) then
**re-locked the design — see "P0 RE-LOCK DECISIONS" below.** Two decisive findings (full detail +
evidence in §2):

1. **There is NO migration *validate* endpoint in the Flowable REST API — on ANY version.**
   `ProcessInstanceResource` exposes exactly one migration method,
   `migrateProcessInstance(id, documentJson) → void` (execute). The engine's
   `ProcessMigrationService.validateMigrationOfProcessInstance(...)` — which returns *precisely* the
   `{ migrationValid, validationMessages:[String] }` shape this design predicted — lives **only in
   the Java API and is never surfaced over REST**. We are REST-only by iron rule. **Therefore the
   "preview is a *real engine call*" claim (§0) and the "Flowable checked this migration" banner
   (§0, §5, SPEC §5) are NOT achievable.** Every engine is, in this doc's own words, a
   "validate-gap engine" — the validate-gap path (§5) is now the *only* path, not the exception.
2. **The migration-document field is `activityMappings`, NOT `activityMigrationMappings`.** The doc
   (and §2 below, pre-spike) had it backwards; `activityMigrationMappings` is the engine builder's
   internal method name. The JSON converter reads `activityMappings`. The execute path itself is
   solid (200 on 6.8/7.1; token confirmed moved v1→v2) — only the *preview/banner* premise breaks.

---

## P0 RE-LOCK DECISIONS (2026-07-09 panel — supersede the pre-spike sections below)
The panel was **unanimous on Option A** and hardened it. These decisions govern P1; the older
§0/§3/§5/§6/§8 text is preserved with inline `⚠️ P0:` markers where superseded.

**Core decision — the "preview" is a BFF *static auto-map check*, an honest estimate, NOT an engine
validation.** The engine is the ground truth only at execute; there is no REST validator to call.

1. **Diff scope = the instance's currently-ACTIVE activity IDs** (from `listExecutions`, exactly as
   change-state reads active activities), diffed against the **target** model — *not* the full
   source-model activity set. The engine only requires mappings for token-holding activities; a
   full-model diff false-flags renamed-but-inactive nodes and adds nothing.
2. **Type-aware + nesting-path-aware, deliberately shallow.** Beyond ID equality the diff compares
   each active node's **type** and **nesting path**. It emits a LOUD, distinct warning (not an
   "unmapped" flag) on the **same-ID / changed-TYPE** case (v1 `step2` userTask → v2 `step2`
   serviceTask): auto-map accepts it, the engine returns **200**, yet the token lands on different
   behavior with no error anywhere — the one *silent-corruption* path both the BFF and the engine
   would otherwise pass. **Honest floor:** active-scoped + type-aware + nesting-aware is the minimum
   sound estimate; a bare ID-set diff is unsound. **Ceiling:** never reimplement the engine's
   migration rules (they vary 6.5→7.x) — stay shallow and labelled.
3. **Advisory-only — the estimate relaxes NO tier-3 rail.** ADMIN floor unconditional every env,
   reason ≥10, typed `MIGRATE` on prod, IRREVERSIBLE badge — identical whether the preview was
   green, red, or never run. A green estimate shortcuts nothing. (This is the single most important
   safety control: it prevents the estimate from silently becoming a gate.)
4. **§5 compare-and-set replaces `validationDigest` with `activityStateDigest`.** Execute
   re-asserts, server-fresh, immediately before the one migrate call: (a) runtime
   `processDefinitionId` == the previewed `fromProcessDefinitionId`, AND (b) `activityStateDigest` =
   hash of the **sorted multiset of `(activityId, executionCount)`** of active executions == the
   digest the preview was computed against. Divergence ⇒ **409 "instance moved since preview —
   re-preview."** (Multiset, not a plain id-set: token multiplicity is under CAS too.)
5. **Execute accepts SEMANTIC inputs only** — `{ target, operatorOverrides[], reason, confirmToken }`
   — **never a client-baked migration document.** The BFF recomputes the static diff server-fresh
   and *rebuilds* the `activityMappings` wire body itself. This binds "what the operator was shown"
   to "what is sent" (TOCTOU defense); a crafted/edited client body can't diverge from the approved
   preview. The preview endpoint sits at the **same ADMIN floor + interactive bulkhead** as execute
   (it reads two models — never a lower-RBAC recon/amplification route).
6. **Audit `migrate/v1` payload** (versioned now so batch slots in later): `engineValidated=false`
   (constant honesty marker), `fromProcessDefinitionId` (pinned), `toProcessDefinitionId` (resolved
   concrete id, pinned) + key/version, `activityMappings` (verbatim sent), `bffAutoMapped` /
   `bffFlagged` (what the operator was shown, labelled estimate), `activityStateDigest` +
   `activeActivities`, `childExecutionCount`, `endpoint`, `restBody`, `reversibility="IRREVERSIBLE"`,
   `warnings`. **Closes:** *ok* → record the **observed post-migrate `processDefinitionId`** (prove
   the move landed; don't infer from the void 200); *failed* → the **verbatim** engine error
   (32 KiB cap), `engineSucceeded=false`; *unknown* (post-dispatch timeout) → Verify-now, **never
   auto-retried** (migrate is non-idempotent, has no idempotency key).
7. **Verify-now for `unknown`** = re-`GET` the runtime instance and compare `processDefinitionId`:
   `==to` → applied (reconcile `unknown→ok`); `==from` **and** `activityStateDigest` unchanged → not
   applied (reconcile `unknown→failed`, safe; operator may re-issue as a fresh migrate); ended/gone
   → fall back to the **historic** instance's `processDefinitionId`. Reconcile is an audit *close*,
   never a re-dispatch.
8. **Capability gating collapses to two states.** DELETE the separate "validate-resource" probe
   (§3 rail 2, §5 Panel B) — it would 404 on every engine, forever; a flag for a universally-absent
   feature is itself dishonest. The `migration` cap (≥6.5) gates **execute**; pre-6.5 (6.3.1
   confirmed no `/migrate` route) → greyed with reason (ProblemDetail, never a dead 404 passthrough).
   **No engine ever earns an "engine checked" badge** — one uniform "Inspector estimate" banner for
   every migration-capable engine (the pre-spike three-state "checked / UNCHECKED / off" model is
   dead).
9. **Definition-versions on-ramp ships in slice-1 regardless** — read-only, count-only Stage-0
   ("37 running on v3 · latest v5"). Standalone diagnostic value (answers "how bad, how many, which
   version" in a bad-deploy incident) and zero mutation surface; de-risks the feature by landing
   value early.
10. **Execute-time backstop (the two-phase engine-as-validator, kept as backstop not primary).**
    When the engine rejects a case the BFF estimate couldn't see, surface the **verbatim** error,
    highlight the named activity in the mapping table, state **"nothing was migrated — the engine
    rolls back the whole document atomically,"** and let the operator map + retry. The pre-flight
    estimate front-loads the common renamed-activity case; the engine's own rejection catches the
    rest — best of both, no serial-reveal-only loop.

**Acknowledged residual limits (RUNBOOK + banner must disclose, cannot be fixed over REST):**
semantic divergence the BFF structurally *cannot* see — a stable-ID activity whose service-task
code / form / required-variables / integration changed migrates 200-OK but may break downstream;
re-subscribed timer/message/signal boundary events reset (a 3h-elapsed 24h timer restarts at 24h);
call-activity children are not migrated; variables are retained untransformed. These are inherent
to REST-only migration and are precisely why the banner never claims success.

**Panel decisions resolved:** Q3 → **moot** (nothing to cache — no engine validate). Q6 → keep an
**explicit "Check mapping" click**, but it computes a BFF model-diff, not an engine digest.
Validate-gap policy → **collapsed** (every engine is validate-gap; one honest banner). On-ramp →
**confirmed in slice-1.** New: **A-advisory-only** + **`activityStateDigest`-CAS** +
**server-recompute wire body** + **`engineValidated:false`** (see §12 table).

---

## 0. The marquee property — and its honest limits
> ⚠️ **P0 (2026-07-09) CONTRADICTS THIS SECTION.** The premise below — "Flowable exposes a
> migration validator … migration's preview is a real engine call" — is **false over REST**. The
> validator is Java-API-only (`ProcessMigrationService.validateMigrationOfProcessInstance`), never
> exposed by `flowable-rest` on 6.3/6.8/7.1. See the top-of-doc P0 callout and §2. Text preserved
> for the panel.

Flowable exposes a migration validator: a POST that statically checks a proposed migration
document against the two definitions. So migration's "preview" is a **real engine call**, unlike
change-state's BFF simulation. **But the panel corrected the claim's scope:**

- The validator is a **static mapping check**, NOT a transactional dry-run-and-rollback. It can
  pass mappings that still fail at apply time (script, variable, runtime-data issues).
- Truthful banner copy: **"Flowable checked this migration — the result below is the engine's own
  validation, not our estimate."** NOT "this migration will succeed." (SPEC §5 copy edit.)
- The validate response is **`{ migrationValid: bool, validationMessages: [String] }`** — bare
  free-text strings, NOT structured objects. Any `level`/`activityId` we show is a BFF-parsed
  heuristic and must be labelled as such; the engine string is always preserved verbatim.

## 1. Scope — v2 slice-1 (deliberately narrow; demand-driven)
IN:
- **Single-instance** migrate, ACTIVE running instances only.
- Target = another **deployed version of the same process key** (default: latest).
- Non-skippable server-side **validate → execute**, always live, never cached.
- **Auto-map first; a validator-driven targeted mapping table**: a manual `from→to` dropdown
  appears ONLY for the specific activities the engine validator flags as unmapped. (Panel A,
  4-1: refuse-if-insufficient would gut the marquee use case — a bad deploy that *renamed* an
  activity is exactly where auto-map fails.)
- **Definition-versions on-ramp** (cohort visibility): a version list with per-version runtime
  instance counts ("37 running on v3 · latest v5"), the entry point that makes single-instance
  migration a real tool rather than a demo. **Elevated into slice-1** (UX panel).

OUT (later slices, each its own design; resolves IMPLEMENTATION-PLAN §474-475):
- **Full side-by-side diagram mapping wizard** — deferred to the batch slice, matching the plan.
  Slice-1 offers only the targeted dropdown for flagged activities, plus an optional read-only
  "show diagram" disclosure, not a dual-canvas mapping editor.
- **Batch / by-definition migration** — deferred entirely (see §7).
- Cross-process-key migration (hard 422 in v2).
- Cross-tenant target (hard refuse).
- `newAssignee` remap, `withLocalVariables`/`withProcessVariables`, `processInstanceVariables`,
  `pre/postUpgradeScript` — explicitly OUT; existing variables are retained as-is by the engine
  and NOT transformed (documented limitation, §9).

**Build gate (demand-driven):** do not start the frontend build on spec. The P0 spike is
always safe to do. The BUILD trigger is a concrete recurring incident: *N instances wedged on a
known-bad deploy version where terminate+restart is unacceptable.* (Panel consensus.)

## 2. Flowable wire shape — ✅ SPIKED LIVE 2026-07-09 (facts below supersede the hypothesis)
Method: deployed `demoMigration:1`/`:2` (v2 renames `reviewTask`→`approveTask`) over REST to each
engine, started a v1 instance, probed both verbs independently, and confirmed every path/field
against the extracted `flowable-rest-*.jar` resource classes + `flowable-engine-*.jar` converter.

**Drift matrix (confirmed):**

| Call | 6.3.1 (:8084) | 6.8.0 (:8081/:8082) | 7.1.0 (:8083) |
|------|---------------|---------------------|---------------|
| `POST /runtime/process-instances/{id}/migrate` (execute) | **404 — no endpoint** (capability cliff, pre-6.5) | **200** ✓ token moved v1→v2, `processDefinitionId` advanced | **200** ✓ |
| any validate path (`…/migrate/validate`, `…/migration/validate`, `runtime/process-instance-migration/validate`, by-definition `repository/process-definitions/{id}/migrate/validate`) | 404 | **404 — does not exist** | **500 "No endpoint" — does not exist** |

- **Execute — CONFIRMED:** `POST /runtime/process-instances/{id}/migrate`, body = the migration
  document (raw JSON, parsed by the engine, not a REST DTO), returns **`void`/empty 200**. Bytecode:
  `ProcessInstanceResource.migrateProcessInstance(String id, String documentJson)` — the ONLY
  migration method on the resource. Capability cliff confirmed: 6.3.1 has no such route (404) → the
  `migration` (≥6.5) capability gate correctly refuses pre-6.5 with a ProblemDetail, never a dead
  404 passthrough.
- **Validate — CONFIRMED ABSENT over REST (all versions).** No single-instance and no by-definition
  validate route exists. `flowable-rest` has **zero** migration validate resource; the engine's
  `ProcessMigrationService.validateMigrationOfProcessInstance(...)` (the source of the
  `{migrationValid, validationMessages:[String]}` shape) is **Java-API only**. ⚠️ This is the P0
  finding that pauses the build (top-of-doc callout).
- **Migration-document field is `activityMappings`** (⚠️ **NOT** `activityMigrationMappings` — that
  was backwards; it is the engine builder's internal method name). Verbatim proof from
  `ProcessInstanceMigrationDocumentConverter`: the converter reads `activityMappings` and
  discriminates the three forms by field presence. The rich-DTO instinct was RIGHT — only the
  wrapper key was wrong:
  ```json
  {
    "toProcessDefinitionId": "orderProcess:5:abc",
    "activityMappings": [
      { "fromActivityId": "reviewTask", "toActivityId": "approveTask" },   // one-to-one
      { "fromActivityIds": ["a","b"], "toActivityId": "merged" },          // many-to-one
      { "fromActivityId": "split", "toActivityIds": ["x","y"] }            // one-to-many
    ]
  }
  ```
  Target selector alternatives (converter-confirmed keys):
  `toProcessDefinitionKey` + `toProcessDefinitionVersion` + `toProcessDefinitionTenantId`.
  OUT-of-scope converter keys present but unused by slice-1: per-mapping `newAssignee`,
  `localVariables`; top-level `processInstanceVariables`, `preUpgradeScript`, `postUpgradeScript`,
  `enableActivityMappings`.
- **No validate response to model.** When a mapping is missing, execute fails **at apply time** with
  a precise verbatim engine message — e.g. `"Migration Activity mapping missing for activity
  definition Id:'reviewTask' or its MI Parent"` (HTTP 500 on 6.8/7.1). That apply-time error is the
  only engine-authoritative "validation" available over REST, and it is what a `failed` audit close
  must surface verbatim.
- **6.x↔7.x drift note for ARCH §2.5:** unknown routes differ — 6.8 returns a clean **404**, 7.1
  wraps them as **500 `"No endpoint POST …"`**. Capability probing must treat *both* as "route
  absent"; do not assume 404 is the only "not-supported" signal.

## 3. Guardrails — pre-flight, BFF-side, run for BOTH validate and execute
Mirror `FlowSurgeryService.planChangeState` ORDER exactly:
1. **RBAC** — ADMIN floor on the engine, **unconditional every environment** (tier-3, like
   terminate/delete/suspend-definition in `ActionVerb`; do NOT copy change-state's tier-2
   OPERATOR/ADMIN-on-prod split). Typed-confirm is the prod-only escalation, not a role change.
2. **Capability** — probe `migration` AND the validate resource **separately**. See §5 for the
   validate-gap policy.
3. **Writable engine** — enabled + not READ_ONLY (R-GOV-04).
4. **Protection** — a SEPARATE fail-closed gate (not folded into RBAC): traps
   `AuditUnavailableException` when the protection/audit Postgres is unreadable (R-AUD-01) and
   surfaces the protection reason into the audit message. Keep it even when the ADMIN floor makes
   it look moot.
5. **Instance restated server-fresh** — must be running (404 if ended) and NOT suspended (409
   "activate first", reuse change-state copy). Multi-instance activities are **NOT** refused
   (unlike change-state) — let the validator speak; MI renames need the richer mapping forms.
6. **Target resolved** — same key, deployed, ≠ current version (409 no-op unless forced); cross-key
   422; cross-tenant refused. Resolve version → concrete `toProcessDefinitionId` and **pin it**.
7. **Call-activity children** — count child/called executions; blast-radius copy must state they
   are **NOT migrated** (they keep their own definition). Never imply the sub-process moved.

## 4. Corrective-actions rails — every one
- **Audit**: `beginPending` AFTER the server-fresh re-plan, BEFORE the migrate call. Payload
  (`migrate/v1`): `fromDefinitionId`, **resolved** `toProcessDefinitionId` (pinned), `endpoint`
  string, `activityMappings`, `validationDigest` (hash of the EXECUTE-TIME re-validation, §5),
  `childExecutionsUnaffected`, `warnings`. Close ok/failed/unknown. (Redaction hook reserved for
  when variable-set lands.)
- **Reason** ≥10 chars, always, every environment.
- **Typed `MIGRATE`** on prod; blast-radius copy states from-version → to-version, instance id,
  and "+N child executions left on their own definition".
- **No auto-retry**: ONE migrate call. Inherit `dispatchAudited`'s `notDispatched()` split —
  pre-dispatch connect failure = `failed` (safe); post-dispatch timeout = `unknown` +
  re-check affordance. Migrate is NON-idempotent; never collapse both into UNKNOWN.
- **Reversibility badge = IRREVERSIBLE**, note: "migrating back is a fresh forward migration to
  the old version, not an undo; work executed under the new version stands."
- **Whitelisted paths** only; no generic proxy.
- **Do-no-harm lane**: validate + migrate on the INTERACTIVE Resilience4j lane (operator waiting);
  validate is a POST but non-mutating — still counts against the interactive bulkhead, never the
  background sampler lane.

## 5. The compare-and-set / banner-honesty rule (panel-critical)
> ⚠️ **P0 RE-LOCK.** There is no engine validate to "re-run server-fresh", so the second bullet
> (re-run the validator, `validationDigest`, "validated at execute time" banner) is unbuildable as
> written. The CAS hardens to **definition-id + token-position re-assert only**. The
> **"validate-gap policy" below is now the UNIVERSAL case**, not the exception: every REST engine is
> validate-gap. Candidate reframe (panel to confirm): the "engine checked this" banner is replaced
> everywhere by the honest **BFF-static-check / "Inspector estimate, not the engine's"** copy.

"Server-fresh re-plan" defends the *definition* only. The real hole: operator validates v3→v5,
but a parallel actor / async job advances the instance to v4 or moves its token between validate
and execute. Mandate:
- Execute re-reads the runtime instance and **asserts** its current `processDefinitionId` still
  equals the `fromDefinitionId` the operator validated (and token position unchanged). If not →
  **409 "instance moved since you validated — re-validate."**
- Execute **re-runs the validator server-fresh**; `validationDigest` = hash of that re-validation.
  If it diverges from what the operator approved (new messages / different auto-map count) →
  **abort with a diff**, never migrate silently under the stale approval. The "engine checked
  this" banner must read **"validated at execute time"** — otherwise the marquee claim is a lie.

**Validate-gap policy (Panel B):** an engine that can migrate but whose version can't
pre-validate is NOT refused. Allow it with the "engine checked this" banner **removed** and
replaced by explicit **"this engine cannot pre-validate — this migrate is UNCHECKED"** + a
heightened confirm. Mirrors change-state's honest "BFF simulation" labelling. (Full refusal is a
defensible do-no-harm alternative; this is a deliberate product call, flagged for sign-off.)

## 6. BFF endpoints (whitelisted, additive)
> ⚠️ **P0 RE-LOCK.** The `/migrate/validate` endpoint below cannot proxy an engine validator (none
> exists over REST). If the panel adopts the BFF-static-check reframe it stays as a BFF-computed
> model-diff (honestly labelled), populated from two `getProcessDefinitionModel` reads — **not** an
> engine round-trip. `engineValidated` is then always `false`; `validationMessages` become
> BFF-authored (still surfaced distinctly from the verbatim engine apply-time error). If the panel
> instead adopts execute-only, this endpoint is dropped entirely. Field shapes below are pending
> that decision.

- `POST /api/instances/{engineId}/{instanceId}/migrate/validate`
  body `{ toDefinitionId? | toDefinitionKey?+toVersion? , activityMappings?[] }`
  → `MigrationValidation { migrationValid, validationMessages:[String] (verbatim),
     parsedMessages:[{level?,activityId?,text}] (BFF heuristic, labelled), engineValidated:bool,
     fromDefinitionId, toProcessDefinitionId, autoMappedCount, flaggedActivities:[], restBody }`
- `POST /api/instances/{engineId}/{instanceId}/migrate/execute`
  body `{ ...target+mappings, reason (≥10), ticketId?, confirmation? }`
  → `ActionResult { auditId, correlationId, outcome, httpStatus, delta }`
  (server-fresh re-plan + re-validate + the §5 compare-and-set assertion before the migrate call.)
- On-ramp read: `GET /api/definitions/{engineId}/{key}/versions` → versions + per-version runtime
  instance counts (count-only queries, Stage-0 discipline).

## 7. Why batch is deferred (unanimous)
By-definition migration returns an async **`Batch`**; work drains via batch-part jobs and you poll
`GET /management/batches/{id}` (+ parts). This breaks the single-call doctrine three ways: (a) the
POST returns *before* any instance migrates ("200 = done" is false); (b) per-part outcomes arrive
over time and fail INDEPENDENTLY — no single ok/failed/unknown to close the audit on; (c) polling a
batch is the opposite of "no auto-retry / UNKNOWN on timeout". It needs its own audit schema
(batch id + per-part reconciliation) and a live-poll read path. **Defer, but version the audit
payload (`migrate/v1`) now** so batch slots in without a schema break. Do NOT scaffold the poll
path against an unspiked, 6.5→7.x-variant wire shape.

## 8. Frontend (slice-1)
- **Entry**: (a) instance Details action menu — "Migrate — move this case to a newer process
  version" (glossary tooltip); (b) the definition-versions view row. Both ship in slice-1.
- **Wizard (3 steps, reuse ChangeStateModal/RestartModal patterns):**
  1. Pick target version (default latest, from the versions endpoint).
  2. Click **Validate** (explicit — Panel: anchors the audit `validationDigest`, keeps the
     "engine approved" moment legible, one interactive-lane call per deliberate action rather than
     per keystroke). Result panel: red summary line ("2 activities can't be auto-mapped") over
     structured message chips (engine text verbatim, `activityId` as a deep-link where parseable);
     the "engine checked this" banner (or the UNCHECKED banner on a validate-gap engine). A
     targeted `from→to` dropdown appears ONLY for each flagged activity; re-validate after mapping.
  3. Reason (≥10) + typed `MIGRATE` on prod → **Execute** → re-fetch instance state (no optimistic
     update). Show-as-cURL is server-computed.
- Optional read-only "show diagram (old | new)" disclosure; the dual-canvas mapping EDITOR is
  deferred to the batch slice.
- Capability-gated: greyed with reason on `migration`-false engines; UNCHECKED-labelled on
  validate-gap engines.

## 9. Known limitations to document (RUNBOOK + tooltip)
- Migration moves tokens + remaps activities; it does **NOT** transform process variables, fix
  external-system integrations (service tasks / listeners / event registries), or handle changed
  variable types / new mandatory variables. Existing variables are retained as-is.
- **Timer / message / signal boundary events are re-subscribed** on migrate — a re-subscribed
  timer resets its due date. Note in the delta + audit.
- Call-activity child instances are not migrated.
- No undo. RUNBOOK carries the recovery procedure (validate-back → forward-migrate to the prior
  version; DB backup guidance for high-stakes prod), not a one-click button.

## 10. Testing (engine-harness — dockerized engines, never mocked)
- **P0 wire-shape spike IT**: seed `demoMigration:1`/`:2` (v2 renames an activity → forces the
  flagged-mapping path; validate-bpmn skill). On 6.x AND 7.x: validate (expect an unmapped
  message), map, validate (valid), migrate, assert runtime token on the v2 activity and
  `processDefinitionId` advanced. Confirm the two path strings + validate-response shape.
- **Guard ITs**: suspended→409; ended→404; same-version→409; cross-key→422; cross-tenant→refused;
  capability-off→409; validate-gap→UNCHECKED path; RBAC<ADMIN→403; prod-no-typed-confirm→refused;
  read-only→403; **instance-moved-since-validate→409** (the §5 CAS assertion); call-activity-child
  present→blast-radius copy + child count.
- **Outcome ITs**: engine-reject verbatim + audit `failed`; post-dispatch timeout → `unknown`.
- ArchUnit / Spotless / ESLint green; schema regen via a throwaway BFF port (parallel-session gotcha).

## 11. Phasing (implementation plan)
- **P0 — spike ✅ DONE 2026-07-09** (live on 6.3.1 / 6.8.0 / 7.1.0, bytecode-verified; see §2 +
  top-of-doc callout). Locked: execute path `POST /runtime/process-instances/{id}/migrate`,
  document field `activityMappings` (three forms), capability cliff at 6.5. **Disproved: any REST
  validate endpoint exists (Java-API only).** `demoMigration` v1/v2 seed authored
  (`docker/processes/demo-migration-v{1,2}.bpmn20.xml`). **Panel RE-LOCK DONE 2026-07-09 → P1
  unblocked; build to the "P0 RE-LOCK DECISIONS" above, NOT the pre-spike §0/§5/§6/§8 text.**
- **P1 — backend + on-ramp (2–3 days):** `MigrationService` (clone `FlowSurgeryService` rail
  order + the §5 `activityStateDigest` CAS, decision P0-3/P0-4); `FlowableEngineClient.migrateInstance`
  (bodiless POST `…/migrate`, field **`activityMappings`**, tenant-threaded) — **NO `validateMigration`
  client method (no such REST endpoint); the "preview" is a BFF static diff over two
  `getProcessDefinitionModel` reads**; richer mapping DTO (three forms); `ActionVerb.MIGRATE_INSTANCE("migrate-instance",
  3, Role.ADMIN, INSTANCE)`; `migrate/preview` (BFF diff) + `migrate/execute` endpoints; versions
  endpoint; capability gating (execute-route only, two states); full guard + outcome + CAS ITs.
  spec-sync: SPEC §5 copy/badge (honest "Inspector estimate" banner, `engineValidated:false`),
  ARCH §5 RBAC, ARCH §2.5 (drift: 404 vs 500 "No endpoint"; DELETE the validate-probe row),
  IMPLEMENTATION-PLAN §474-480, RUNBOOK recovery + the acknowledged-limits disclosure.
- **P2 — frontend wizard (2 days):** gen:api; 3-step MigrateModal; explicit Validate; structured
  message chips + honesty/UNCHECKED banners; targeted mapping dropdowns for flagged activities;
  typed-MIGRATE; definition-versions view + entry (b); Playwright e2e (URL-predicate mocks) + one
  live smoke.
- **LATER (own design docs):** batch/by-definition (async `Batch` poll + per-part audit), full
  side-by-side diagram mapping editor, cross-key, assignee/variable remap.

## 12. Panel decisions (provenance)
| # | Decision | Verdict | Voices |
|---|---|---|---|
| Q1 | Mapping UI in slice-1 | **Validator-driven targeted table** (dropdown only for flagged activities); full diagram wizard deferred | A, 4-1 (Gemini×2, Safety, Flowable-API vs UX) |
| Q2 | Cross-key | **Hard 422**, its own design later | unanimous |
| Q3 | Cache validate | **MOOT (P0 re-lock)** — no engine validate exists over REST; the BFF estimate is recomputed per "Check mapping" click, nothing to cache | resolved |
| Q4 | Batch | **Defer entirely**; version the audit schema now; don't scaffold poll | unanimous |
| Q5 | Migrate-back | **Manual re-run**, no one-click (would fake an undo) | unanimous |
| Q6 | Validate trigger | **Explicit "Check mapping" click (P0 re-lock)** — computes a BFF model-diff (active-scoped, type+nesting aware), NOT an engine digest | resolved |
| Q7 | RBAC | **Tier-3 ADMIN unconditional every env**; typed-confirm is the prod escalation | unanimous |
| — | Validate-gap engine | **COLLAPSED (P0 re-lock)** — no validator on ANY engine; delete the separate probe; one uniform "Inspector estimate" banner; no "checked" badge ever | unanimous |
| — | On-ramp | **Definition-versions view into slice-1** (cohort visibility) | UX; unopposed |
| **P0-1** | Preview mechanism | **BFF static auto-map check = honest estimate; engine is ground truth at execute only** | unanimous (5 seats) |
| **P0-2** | Estimate authority | **Advisory-only — relaxes NO tier-3 rail**; a green preview shortcuts nothing | Safety; unopposed |
| **P0-3** | Execute-time CAS | **`activityStateDigest`** (multiset of (activityId,executionCount)) + def-id re-assert, replacing the impossible `validationDigest` | Safety; unopposed |
| **P0-4** | Wire-body trust | **Execute takes semantic inputs only; BFF recomputes the migration document server-side** (TOCTOU bind shown→sent) | Safety; unopposed |
| **P0-5** | Same-ID/changed-TYPE | **Loud distinct BFF warning** — the one silent-corruption path (engine 200s, token on wrong behavior) | Flowable-REST honesty; unopposed |

## 13. Parallel-build compatibility with Registry CRUD (v2)
Migration and Registry CRUD (`REGISTRY-CRUD.md`) are being built in parallel sessions. The
seams below are chosen so neither blocks the other; the collision points are called out with
the resolution.

- **Flyway — no collision.** CRUD claims **V7** (the `engine_registry` table + lifecycle/
  tombstone columns, REGISTRY-CRUD.md §10). Migration adds **NO new table** — it reuses
  `audit_entry` (payload schema `migrate/v1`) and `protected_instance`. Migration therefore
  claims **no `V*__*.sql` file at all**, so the two features cannot race on a version number.
  *(Iron rule: schema is Flyway-only; migration introducing a table later would take the next
  free version at that time, not reserve one now.)*
- **`ActionVerb.java` — additive, low risk.** Migration appends one enum constant
  `MIGRATE_INSTANCE("migrate-instance", 3, Role.ADMIN, INSTANCE)`. CRUD's admin surface is a
  separate `AdminEnginesController` (REGISTRY-CRUD.md §9) governing registry lifecycle, not the
  per-instance verb catalog, so it does not touch `ActionVerb`. If both ever append here, it is
  a trivial two-line enum merge (append-only, no reordering).
- **`EngineRegistry` / capabilities — read-only overlap.** Migration only *reads*
  `registry.require(...)`, `registry.healthOf(...).capabilities()`, and the `migration` flag
  (already present in `EngineCapabilities`). CRUD *mutates* the registry (add/edit/disable
  engines) behind its own service. Migration must treat the registry as an interface it reads,
  never assume a static in-memory list — which it already does. **Coordination note:** if CRUD
  makes the registry DB-authoritative/hot-reloadable, migration's server-fresh `registry.require`
  keeps working unchanged (it re-reads per call). No shared mutable state.
- **`FlowableEngineClient` — additive methods.** Migration adds `validateMigration` /
  `migrateInstance`; CRUD adds none to this client (it manages registry rows, not engine calls).
  No overlap.
- **Generated `schema.d.ts` — expected merge noise, resolved by regen.** Both features add DTOs
  and both regenerate the OpenAPI types. A textual conflict on `schema.d.ts` is normal and is
  resolved by re-running `npm run gen:api` against the merged backend, never by hand-editing
  (iron rule). Sequence: whoever merges second regenerates.
- **`IMPLEMENTATION-PLAN.md` v2 section — small textual proximity.** Both edit the v2 bullet
  list. Migration's plan edits are confined to the Migration bullet (§474-475) + the
  design-doc pointer; CRUD's are confined to its own bullets (§567+). Keep edits bullet-local to
  avoid a merge conflict; if one occurs it is a paragraph-level resolve.
- **Frontend nav/routes — additive.** Migration adds an instance-Details action + a
  definition-versions route; CRUD adds an admin/registry route. Different router subtrees.
- **Net:** the only *guaranteed* touch-both files are `ActionVerb.java` (append-only) and
  `schema.d.ts` (regen). Both have mechanical, non-semantic resolutions. **The two features are
  safe to build on independent branches off `main` and merge in either order.**
