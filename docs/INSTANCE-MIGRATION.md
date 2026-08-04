# Instance Migration — design (v0.4, P0-spiked + panel-RE-LOCKED; §14 adds the named-findings taxonomy design, issue #349)

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
8. **Dangerous-set re-auth — EXECUTE ONLY (issue #295, IDP-SECURITY.md §5, R-SAFE-07).** Migrate is
   tier-3 by spec (§0), so a stale OIDC session must re-authenticate before it can execute — same
   protocol as terminate/delete/suspend-definition. `MIGRATE` is not (and cannot be) an `ActionVerb`,
   so `CorrectiveActionService`'s `verb.tier() >= 3` branch never reaches it; `MigrationService`
   calls `reauth.enforce(auth)` itself, in `execute()`, right after `plan()` returns (steps 1–7
   above have all run) and BEFORE the §5 compare-and-set / reason / typed-confirm rails below — a
   stale operator is challenged at verb intent, never after typing the confirm token. This was
   found MISSING entirely in the initial S1/S2 build (spec said tier-3, no gate existed, no
   documented exemption) — landed after the fact, not part of the original P0 design. **Deliberately
   NOT on `preview()`**: it is read-only and shares `plan()` with execute, so gating `plan()` itself
   would gate preview too, fighting the `/api/me` reauth-hint protocol the SPA pre-empts with at
   modal open.

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

---

## 14. Named-findings taxonomy for the preflight estimate (issue #349 design, 2026-08-04)

> Status: **design, docs-only — gates build slice #355.** Extends (never relaxes) the P0 re-lock.
> The literature motivation and phasing live in issues #349/#356. Everything in §14 was grounded
> in a live simulation against a real dev engine (§14.2); measured facts are labelled **[M*]**,
> design proposals are labelled **proposal**. spec-sync lockstep edits (SPEC §5 row wording,
> ARCH §4 table, IMPLEMENTATION-PLAN) land WITH the #355 build, mirroring how §§0–13 were synced.

### 14.0 The governing restraint — read first

The P0 re-lock ceiling governs every line of this section: **never reimplement the engine's
migration rules — stay shallow and labelled** (decision P0-1 floor/ceiling, §2). Its corollaries,
made explicit for this taxonomy:

- **P14-A — zero new engine calls.** Every finding is computed ONLY from data the preview
  already fetches: the two `BpmnStructure` model reads (`/model` JSON + `/resourcedata` XML)
  and the instance's active-execution list (`GET /runtime/executions?processInstanceId=`,
  capped 200). A finding whose predicate would need another read does not exist.
- **P14-B — severity moves only TOWARD the backstop.** Where live calibration proved the
  engine accepts a case the current pre-check refuses (two such cases found, §14.2 M3/M4),
  the finding is downgraded blocker→warning and the engine's own atomic apply-time rejection
  (re-lock decision 10: "nothing was migrated — the engine rolls back the whole document")
  remains the safety net. The taxonomy introduces **no new blocker** and never converts a
  warning into a promise of success.
- **P14-C — when in doubt, label.** A risk the BFF cannot observe over REST is WORDING inside
  a finding ("the estimate cannot know…"), never a new check. §14.5 lists every candidate check
  that was considered and dropped under this rule.
- **P14-D — the estimate stays advisory (re-lock decision 3, restated §14.6).** The only
  "blocking" the pre-check performs — before and after this design — is **document-construction
  impossibility**: a token-holding leaf activity with no counterpart id and no operator mapping
  leaves the BFF with *nothing sendable* (auto-map cannot invent a target; the engine rejects
  the whole document, [M7]). That refusal is about being unable to build the wire body the
  operator approved, NOT about predicting the engine's verdict. No finding, green or red,
  touches any tier-3 rail.

### 14.1 What exists today (measured baseline)

The current pre-check (`MigrationDiff` / `ActivityDiffEntry`) classifies each active activity
id into five statuses: `AUTO_MAPPED`, `MAPPED_BY_OVERRIDE`, `FLAGGED_UNMAPPED` (blocker),
`TYPE_CHANGED` (warning), `NESTING_CHANGED` (warning). The audit payload carries them as the
ad-hoc string lists `bffAutoMapped` / `bffMappedByOverride` / `bffWarnings`. (The issue text's
"`bffFlagged`" is the preview-side `FLAGGED_UNMAPPED` status: a flagged activity can never reach
an execute audit row, because execute refuses 422 `unmapped-activities` first — [M8].) These are
exactly the "ad-hoc flags" #349 asks to replace with typed, citable findings.

### 14.2 Simulation method + measured facts (auditable)

**Method.** Live run 2026-08-04 against the dev harness — engine-a, flowable-rest **6.8.0**
(:8081) with the BFF from this checkout on :8085 (dev Postgres :5433), then the engine-side
calibrations repeated on flowable-rest **7.1.0** (:8083, `flowable-7` profile). Seeded strictly
over REST (`POST /repository/deployments`, `POST /runtime/process-instances`) — no `ACT_*`
access. **Calibration scope (panel-demanded disclaimer):** the two migration-capable harness
versions are 6.8.0 and 7.1.0; **6.5–6.7 are not in the harness and remain uncalibrated** — every
severity decision below states which versions it was proven on, and the finding wording never
claims more than that. Fixtures:
the committed two-version `demoMigration` pair (`docker/processes/demo-migration-v{1,2}.bpmn20.xml`,
deployed as v69/v70 on this engine) plus three THROWAWAY probe processes (not committed; #355
turns them into committed IT fixtures, §14.9):

- `taxProbeA` v1: `start → subProcess scopeA { startA → stepA(userTask) → endA } → end`;
  v2: `scopeA` **removed entirely**, `stepA` keeps its id at the process root.
- `taxProbeB` v1: `stepT` is a **userTask**; v2: same id `stepT`, now a **sync serviceTask**
  (`flowable:expression="${1 + 1}"`).
- `taxProbeC` v1: `stepC(userTask)` with interrupting **boundary timer** `bndC` (PT72H);
  v2: `stepC` unchanged, boundary timer **removed**; v3: **identical** boundary timer to v1.

All migrations below ran on the dev engine only. Facts:

- **[M1] Preview wire shape (real BFF `POST …/migrate/preview`).** For a `demoMigration`
  v69 instance parked on `reviewTask` targeting v70 (rename → `approveTask`):
  ```json
  { "engineId":"engine-a", "fromDefinitionId":"demoMigration:69:…", "toProcessDefinitionId":"demoMigration:70:…",
    "engineValidated":false, "executable":false,
    "activities":[ { "fromActivityId":"reviewTask", "fromType":"userTask", "fromName":"Review order",
        "status":"FLAGGED_UNMAPPED", "toActivityId":null, "toType":null,
        "detail":"No activity with id 'reviewTask' exists in the target version — …", "blocker":true, "warning":false } ],
    "targetActivities":[ {"id":"start",…}, {"id":"approveTask","name":"Approve order","type":"userTask"}, {"id":"end",…} ],
    "activityStateDigest":"080769936dab…", "callActivityChildCount":0,
    "restBody":{ "toProcessDefinitionId":"demoMigration:70:…", "activityMappings":[] },
    "summary":"Migrate this instance from v69 to v70. 1 active activit(ies) can't be auto-mapped — pick a target for each.",
    "banner":"Inspector pre-check — this is not a Flowable validation. …" }
  ```
  With the operator mapping supplied, the same entry becomes `MAPPED_BY_OVERRIDE`,
  `executable:true`, and `restBody.activityMappings` carries
  `{"fromActivityId":"reviewTask","toActivityId":"approveTask"}`; execute then landed the token
  on `approveTask` (engine 200; post-migrate re-read observed `demoMigration:70:…`).
- **[M2] Scope executions and boundary events ARE in the active-activity list** (identical
  shape on 6.8.0 and 7.1.0). A token on `stepA` inside `scopeA` yields THREE runtime
  executions: the instance root (`activityId:null`, filtered out), the **scope execution**
  (`activityId:"scopeA"`) and the leaf (`activityId:"stepA"`). A boundary timer yields a CHILD
  execution of the task execution (`activityId:"bndC"`, parent = `stepC`'s execution). The diff
  therefore classifies `scopeA` (`fromType:"subProcess"`) and `bndC`
  (`fromType:"boundaryEvent"`) as active activities.
- **[M3] Removed-scope migration: the engine accepts what the pre-check refuses** (proven on
  **6.8.0 AND 7.1.0**). Preview for `taxProbeA` v1→v2 returned `scopeA → FLAGGED_UNMAPPED`
  (**blocker**, `executable:false`) plus `stepA → NESTING_CHANGED` (`[scopeA] → []`).
  Engine-direct `POST …/migrate` with **empty** `activityMappings` returned **200** on both
  versions; afterwards the token sat on `stepA` at the root, the scope execution was gone,
  `processDefinitionId` advanced. The current blocker on the scope execution is a **false
  blocker**: through the BFF this legitimate migration is impossible today (422), while the
  engine accepts it with auto-map alone.
- **[M4] Removed-boundary migration: same false blocker** (proven on **6.8.0 AND 7.1.0**).
  Preview for `taxProbeC` v1→v2 returned `bndC → FLAGGED_UNMAPPED` (**blocker**) plus
  `stepC → AUTO_MAPPED`. Engine-direct migrate with empty mappings: **200** on both versions;
  the `bndC` execution AND its timer job were silently dropped (timer-jobs count 1→0) — the
  deadline protection vanished without any warning anywhere.
- **[M5] Boundary timer clock across an unchanged model is VERSION-DIVERGENT.** `taxProbeC`
  v1→v3 (byte-identical boundary timer), engine 200 on both versions, but: on **6.8.0** the
  timer job was recreated with `dueDate` moved from `2026-08-07T07:09:29Z` (instance start +
  72h) to `2026-08-07T07:11:12Z` (**migration time** + 72h) — the clock RESET; on **7.1.0**
  the post-migrate `dueDate` was **unchanged** (`2026-08-07T07:22:27.011Z`, the original
  start-relative deadline) — the clock was PRESERVED. Two consequences: (a) any "boundary
  events *changed*" check would be misleadingly narrow (on 6.8 the reset happens with zero
  model change); (b) the reset itself is engine-version behavior the estimate must state as
  "may", never "will" — and must never model per-version (ceiling).
- **[M6] Same-id type change: the new behavior can run IMMEDIATELY** (proven on **6.8.0 AND
  7.1.0**). `taxProbeB` v1→v2 (on 6.8 through the full BFF execute rails; engine-direct on
  7.1): engine 200, and the sync serviceTask **executed at migrate** — the instance ran to
  completion before the BFF's post-migrate re-read
  (`responseSnippet: {"observedProcessDefinitionId":"(instance ended)"}`; history confirms
  `endTime` set, definition = v2, on both versions). "The token lands on different behavior"
  understates it: the different behavior can EXECUTE as a side effect of the migrate call
  itself.
- **[M7] The engine's apply-time rejection (the backstop), verbatim on this stack.** Migrating
  the `demoMigration` instance without a mapping: HTTP **500**,
  `{"message":"Internal server error","exception":"Migration Activity mapping missing for
  activity definition Id:'reviewTask' or its MI Parent"}` — atomic (nothing moved; the
  subsequent mapped execute succeeded on the same instance). Note the engine's own wording
  names leaf ids "**or its MI Parent**" as the mapping-requiring set.
- **[M8] Audit contract as actually written.** The execute audit row's payload key set
  (captured from `GET /api/instances/{engineId}/{id}/audit`): `schema`, `engineValidated`,
  `fromProcessDefinitionId`, `toProcessDefinitionId`, `toProcessDefinitionKey`,
  `toProcessDefinitionVersion`, `activityMappings`, `bffAutoMapped`, `bffMappedByOverride`,
  `bffWarnings`, `activityStateDigest`, `activeActivities`, `childExecutionsUnaffected`,
  `businessKey`, `endpoint`, `restBody`, `reversibility`. The schema discriminator string is
  **`migrate-instance/v1`** (`MIGRATE_ACTION + "/v1"`); this doc's §7/§12 shorthand
  "`migrate/v1`" refers to that string. There is no `bffFlagged` key in any real row (see §14.1).

### 14.3 The taxonomy (proposal)

Findings are **typed annotations** carried per classified activity (and, for `INFO`, per
instance); the mapping-mechanics statuses (`AUTO_MAPPED` / `MAPPED_BY_OVERRIDE` /
`FLAGGED_UNMAPPED`) remain the execution-document machinery. Severities:

- **`BLOCKER_ADVICE`** — execute refuses 422 until the operator supplies a mapping, ONLY
  because no wire document can be built (P14-D). Not an outcome prediction. Panel-demanded
  user-facing wording (so the name is never read as an engine verdict): *"The Inspector cannot
  build a migration instruction for this activity — there is nothing to send. The engine is
  known to reject documents missing it. Pick a target mapping."*
- **`WARNING`** — migrates; the operator should look first. Never blocks.
- **`INFO`** — a factual consequence of migrating this instance. Never blocks.

Classification runs per active execution id, first by **source-model node kind** (leaf /
scope-container / boundary event — all three provably present in the active list, [M2]), then
down each ladder; first match wins.

| Code | Severity | Predicate (all inputs already fetched — P14-A) | Criterion approximated (§14.7) | Provenance |
|---|---|---|---|---|
| `UNMAPPED_ACTIVE_ACTIVITY` | `BLOCKER_ADVICE` | LEAF active id (source type ∉ {`subProcess`,`transaction`,`adHocSubProcess`} ∪ {`boundaryEvent`}) with `!target.has(id)` and no operator override | state-mapping totality: every token needs a well-defined target position | [M1] [M7] — the engine itself rejects exactly this |
| `ACTIVE_SCOPE_REMOVED` | `WARNING` | active id whose source type IS a scope container, `!target.has(id)`, and the id is NOT a multi-instance root (`multiInstanceScopeOf(id) ≠ id`) | change-region reasoning: the enclosing region dissolves; tokens re-home | [M2] [M3] — engine accepted with empty mappings on 6.8.0 AND 7.1.0; **downgraded from today's false blocker (P14-B; see the downgrade-asymmetry note below)** |
| `ACTIVE_IN_REMOVED_SCOPE` | `WARNING` | LEAF active id that maps by id, but some scope id on its SOURCE `nestingPath` has `!target.has(scopeId)` (takes precedence over `NESTING_PATH_CHANGED`) | compliance/state-mapping: position preserved, containing state is not | [M2] [M3] — `stepA` `[scopeA] → []`, engine 200 |
| `NESTING_PATH_CHANGED` | `WARNING` | same id + type, `sourcePath ≠ targetPath`, every source-path scope still exists in the target | change-region reasoning (moved between still-existing regions) | existing status, retyped; live-proven shape in [M1]-family runs |
| `TYPE_CHANGED_SAME_ID` | `WARNING` (loud, distinct — decision P0-5 unchanged) | same id, different source/target element type | compliance violated silently: same position, different behavior | [M6] — wording MUST now say the new behavior **can execute immediately during the migrate call** (calibrated: a sync serviceTask ran the instance to completion) |
| `BOUNDARY_SUBSCRIPTION_REMOVED` | `WARNING` | active id whose source type is `boundaryEvent` with `!target.has(id)` | loss of an event-region: a deadline/compensation path silently disappears | [M2] [M4] — engine 200 on 6.8.0 AND 7.1.0, timer job dropped without trace; **downgraded from today's false blocker (P14-B; see the downgrade-asymmetry note below)** |
| `BOUNDARY_CLOCK_RESET` | `INFO` | ANY active `boundaryEvent`-typed execution exists (changed or not) | temporal-state non-preservation under re-subscription | [M5] — **version-divergent**: an IDENTICAL PT72H timer restarted at migrate time on 6.8.0 but kept its original due date on 7.1.0. Wording says "may reset", never "will"; the estimate never models per-version behavior (ceiling). Instance-specific surfacing of the existing banner clause |

**MI-root retention (deliberate non-downgrade):** an active scope id that IS a multi-instance
root and is absent from the target stays `UNMAPPED_ACTIVE_ACTIVITY` (`BLOCKER_ADVICE`). The
primary reason is P14-B itself, applied symmetrically: **a severity downgrade requires
calibration evidence, and no MI case was calibrated** — downgrading it would be an unproven
prediction that the engine tolerates it, which is exactly the rule-guessing the ceiling forbids.
(Secondary, non-load-bearing support: the engine's own rejection text names "…or its MI Parent"
as mapping-requiring, [M7].) `multiInstanceScopeOf` already exists in `BpmnStructure` (fed by
the `/model` JSON, the mandated MI source), so the predicate is free. The #355 MI fixture
(§14.9) gathers the evidence; if the engine proves tolerant, a later `taxonomyVersion` bump
downgrades it THEN — never speculatively now.

**The downgrade-asymmetry note (panel-probed):** the two blocker→warning downgrades are
calibrated on 6.8.0 and 7.1.0 only; 6.5–6.7 are uncalibrated (§14.2). They stand anyway,
because the two failure modes are not symmetric. Keeping the blocker on an engine that accepts
the migration makes a legitimate recovery **impossible through the BFF** (422 before any engine
contact — no feedback, no path forward; [M3]/[M4] are precisely the bad-deploy shapes this
feature exists for). Downgrading on an engine that turns out to reject it costs one execute
that fails **atomically** with the engine's verbatim message surfaced and audited — which is
re-lock decision 10's *designed* backstop path, not an accident. The finding wording carries
the residual honestly: *"accepted without a mapping by the engines we calibrated (6.8/7.1);
your engine may still reject at execute — atomically, with its exact message shown here."*
Per-version severity switching was considered and rejected: a version→behavior matrix IS a
reimplementation of engine migration rules (the ceiling), and it would rot silently as engines
patch.

**Net behavioral delta of the whole taxonomy:** two false blockers removed ([M3]/[M4] — cases
the BFF today cannot execute at all but the engine accepts), everything else is naming, honest
wording, and audit typing. No new checks beyond the source-node-kind split and the
`nestingPath`-scope-existence lookup, both over data already parsed.

### 14.4 Honest-wording requirements (issue point 1)

Every finding's `detail` must state what the estimate can and cannot know, in this shape —
"what we compared / what the engine will do about it / what nobody can see over REST":

- `ACTIVE_SCOPE_REMOVED` / `ACTIVE_IN_REMOVED_SCOPE`: "…the engine accepted this shape without
  a mapping in live calibration (6.8/7.1). Scope-local variables and event subscriptions of the
  removed scope have no target scope — **the estimate does not read execution-local variables
  and cannot know** what state is lost."
- `BOUNDARY_SUBSCRIPTION_REMOVED`: "…the deadline/compensation protection this event provided
  disappears at migrate, with no error anywhere ([M4])."
- `BOUNDARY_CLOCK_RESET`: "…boundary events are re-subscribed at migrate even when unchanged;
  a timer's clock **may restart** from the migrate call — observed on 6.8 ([M5]: a 72h timer
  elapsed 2 minutes restarted at 72h) while 7.1 preserved the original due date. **The estimate
  cannot know which behavior your engine exhibits**, and cannot distinguish timer from
  message/signal boundary events (the event-definition child is not parsed — deliberate,
  §14.5), so this is stated for all."
- `TYPE_CHANGED_SAME_ID`: "…the engine returns success and the new implementation **can execute
  immediately as part of the migrate call** ([M6]) — verify the new behavior is intended NOW,
  not later."
- The §5 banner stays verbatim (it already discloses parked jobs, parallel-join state, and
  semantic drift) — findings sharpen it per instance, never replace it.

### 14.5 Considered and DROPPED (the ceiling in action — issue point 2)

Each candidate below is dropped under P14-C, with the residual risk labelled instead:

- **Gateway/parallel-join token arithmetic** (would flag joins that can never fire after
  migration): predicting join satisfiability IS the engine's migration/execution semantics
  (varies 6.5→7.x). DROPPED — banner clause ("parallel-join state") stands.
- **Behavior/attribute drift at same id+type** (changed `flowable:expression`, listeners,
  assignee, async flags, forms): the attribute space is unbounded and engine-version-variant;
  diffing it is a slow slide into reimplementing the deployer. DROPPED — this is precisely the
  acknowledged "stable-ID semantic divergence" residual limit; stays banner-labelled.
- **Variable-scope loss analysis** (which execution-local variables die with a removed scope):
  needs per-execution variable reads (new engine calls — violates P14-A) plus the engine's
  variable-scoping rules. DROPPED — became the "cannot know" wording in §14.4.
- **Boundary attachment-diff** (`attachedToRef` re-parse to detect an event moved to another
  activity, or timer→message definition changes): would need new XML parsing for a marginal
  refinement of `BOUNDARY_SUBSCRIPTION_REMOVED`/`BOUNDARY_CLOCK_RESET`, and [M5] proves the
  dominant risk (clock reset) is change-independent anyway. DROPPED — id-presence form only.
- **MI mapping-rule modelling** (one-to-many/many-to-one legality, MI-body re-entry): engine
  rules. DROPPED beyond the single MI-root blocker retention argued in §14.3.
- **Event-subprocess / event-registry / message-signal re-correlation analysis**: engine +
  registry semantics. DROPPED — banner.
- **Call-activity child reasoning**: stays what it is today — a counted blast-radius guard
  ("N child instance(s) are NOT migrated"), not a finding; children are a different instance's
  state.
- **Any form of history/compliance replay** (the literature's full compliance check — §14.7):
  requires the execution history AND the engine's replay semantics. Structurally above the
  ceiling; this is the line the whole taxonomy exists to approximate *shallowly*, not cross.

### 14.6 Advisory-only doctrine restated (issue point 3 — verbatim rails)

The taxonomy changes what the estimate SAYS, never what the rails DO. Unchanged and untouched
by any finding, any severity, any color of the estimate:

- ADMIN floor, **unconditional every environment** (decision Q7/P0); reason ≥10 chars; typed
  **`MIGRATE`**/business-key confirm on prod; IRREVERSIBLE badge; dangerous-set reauth at
  execute (§3.8).
- The §5 CAS: `expectedFromDefinitionId` + `activityStateDigest` both MANDATORY (400
  `preview-required`), 409 on divergence. The digest input (sorted multiset of
  `(activityId, executionCount)`) is NOT altered by the taxonomy — scope and boundary
  executions stay IN the digest ([M2] is exactly why: their movement is real state movement).
- Execute accepts SEMANTIC inputs only; the BFF rebuilds the wire body server-fresh (P0-4).
- ONE engine call, never auto-retried; `unknown` ⇒ verify-now (P0-6/7).
- **`engineValidated=false` — constant, forever** ([M8]); no finding ever implies the engine
  checked anything. A green estimate (zero findings) shortcuts NOTHING; a red estimate gates
  NOTHING beyond the P14-D document-construction refusal that exists today.

### 14.7 Criteria mapping + citation honesty

The two R3 reference papers are **paywalled and were not obtainable** for this design pass
(checked the local papers folder and the research MCP on 2026-08-04; neither PDF present). Per
issue instruction the mapping below therefore works from the criteria **as cited in open
literature**, at named-concept granularity only — no specific claim is attributed to either
paper's text, and no page/section citations are given:

- *Correctness criteria for dynamic changes in workflow systems — a survey*, Rinderle,
  Reichert, Dadam, DKE 50(1), 2004 — DOI `10.1016/j.datak.2004.01.002`: the **compliance**
  family (an instance may adopt a changed schema when its current state/history has a valid
  counterpart under it) and change-region/state-mapping reasoning.
- *ADEPTflex — Supporting Dynamic Changes of Workflows Without Losing Control*, Reichert &
  Dadam, JIIS 10, 1998 — DOI `10.1023/A:1008604709862`: structural change operations with
  correctness guarantees (foundational for "deleting a region containing active work is the
  dangerous case").

The taxonomy approximates ONLY the token-position fragment of compliance: "does every live
token have a well-defined, same-natured position in the target schema, and does its containing
region survive?" Full compliance (history replay) and the papers' formal machinery are
explicitly NOT implemented — that is the ceiling, not an omission. `BOUNDARY_CLOCK_RESET` has
no criterion in this family; it is an empirically-proven engine behavior ([M5]) surfaced as
fact.

### 14.8 Audit `migrate/v1 → v2` payload evolution (issue point 4)

Contract precedent (R-AUD-02: "per-verb versioned schemas"; §7: version now so batch slots in;
[M8]: discriminator = `migrate-instance/v1`): additive keys may land within a version; a
**replaced or retyped** key bumps the version. Findings replace `bffWarnings`, so:

- **`schema: "migrate-instance/v2"`**, changes relative to v1:
  - **ADD `bffFindings`**: `[{ "code", "severity", "activityId", "detail" }]` — the typed
    findings the operator was shown at the preview execute was CAS-bound to. `BLOCKER_ADVICE`
    entries still can never appear in an execute row's findings **except** as
    `MAPPED_BY_OVERRIDE`-resolved history (execute refuses 422 while any is unresolved — [M8]);
    warnings/info CAN now appear for the two downgraded cases ([M3]/[M4]) that previously could
    not reach execute at all.
  - **ADD `taxonomyVersion: 1`** — the findings-vocabulary generation, so a future vocabulary
    change is detectable without another schema bump. Bump `taxonomyVersion` on ANY vocabulary
    semantics change: code additions, code renames, **and severity reassignments of an existing
    code** (panel-demanded — an audit reader must be able to interpret a historical row's
    severity under the vocabulary that produced it). Payload KEY changes bump the schema
    string itself.
  - **REMOVE `bffWarnings`** (fully derivable from `bffFindings`; keeping both invites drift).
  - **KEEP unchanged**: `engineValidated:false`, `fromProcessDefinitionId`,
    `toProcessDefinitionId`/`Key`/`Version`, `activityMappings`, `bffAutoMapped`,
    `bffMappedByOverride`, `activityStateDigest`, `activeActivities`,
    `childExecutionsUnaffected`, `businessKey`, `endpoint`, `restBody`, `reversibility`.
- No Flyway change (payload is `jsonb`); audit readers discriminate on `schema` and render v1
  rows as today. The batch reservation (§7, `bulk/…` schema family) is unaffected.
- Preview DTO (build #355): additive `findings[]` on each `ActivityDiffEntry` projection plus
  instance-level `findings[]` for `BOUNDARY_CLOCK_RESET`; existing `status`/`blocker`/`warning`
  fields stay (frontend regen via `npm run gen:api`, never hand-edited).

### 14.9 Test obligations for build #355 (engine-harness, never mocked)

- Commit the three probe topologies of §14.2 as IT fixtures (validate-bpmn rules: DI added,
  stable keys) and assert the CALIBRATED behaviors: removed-scope and removed-boundary cases
  execute **through the BFF** with warnings (the false blockers are gone) and land where [M3]/
  [M4] observed; `TYPE_CHANGED_SAME_ID` execute observes the ended/advanced instance ([M6]);
  `BOUNDARY_CLOCK_RESET` info present on every engine leg, with the timer `dueDate` RECORDED
  per version, not asserted universally — [M5] is version-divergent (moved on 6.8, preserved
  on 7.1), so a universal assert would enshrine one engine's behavior (Awaitility with
  explicit bounds, never sleep).
- An MI-root fixture proving the retained blocker path refuses 422 (and, if the engine
  accepts an unmapped MI-root shape anywhere, the backstop IT records the verbatim outcome —
  evidence for a future `taxonomyVersion` bump, not silently absorbed).
- Guard ITs of §10 unchanged — no rail moved, so no guard test may change EXCEPT the two
  422-`unmapped-activities` assertions that covered the false blockers, which flip to
  warning-carrying 200-preview/execute paths.

### 14.10 Panel review record (issue-mandated, 2026-08-04)

Independent adversarial review by the two authorized reviewer models, each explicitly asked to
attack ceiling-creep in the proposed checks:

- **Gemini (gemini MCP, `gemini-2.5-flash`; `gemini-2.5-pro` was 429-rate-limited)** —
  **APPROVE-WITH-CHANGES**, five demands, dispositions:
  1. *Revert the two blocker→warning downgrades (calibration was 6.8-only).* **Partially
     accepted:** the calibration was EXTENDED to 7.1.0 in response (all four probe migrations
     re-run live; [M3]/[M4]/[M6] now hold on both majors, [M5] found version-divergent), and
     the 6.5–6.7 gap is now a mandatory disclaimer (§14.2) + per-finding wording. The revert
     itself is **rejected** with the downgrade-asymmetry argument recorded in §14.3: a kept
     false blocker makes the recovery impossible with zero engine feedback; a wrong warning
     costs one atomic, verbatim-surfaced engine rejection — the designed backstop (decision
     10). Per-version severity switching rejected as ceiling-crossing.
  2. *Downgrade the MI-root retention to warning (calling the blocker rules-creep).*
     **Rejected — inverted:** P14-B requires calibration evidence for any downgrade, and no MI
     case was calibrated; downgrading on speculation would be the actual rule-guessing. §14.3's
     justification was REWRITTEN so the engine's error text is secondary, and the #355 MI
     fixture is the designated evidence-gatherer for a later `taxonomyVersion` downgrade.
  3. *Prominent calibration-scope disclaimer.* **Accepted** — §14.2 method block + per-finding
     provenance cells + §14.4 wording.
  4. *`BLOCKER_ADVICE` user-facing wording must not read as an engine verdict.* **Accepted** —
     recommended copy added to the severity definition (§14.3).
  5. *`taxonomyVersion` must also bump on severity reassignments.* **Accepted** — §14.8.

  A confirmation round put all five dispositions (including both rejections and their
  arguments) back to the same reviewer: final verdict **ACCEPT**, no remaining objections
  ("the asymmetry argument … is compelling"; the MI-root rejection judged "principled and
  correct").
- **Copilot / GitHub Models (copilot MCP)** — **UNAVAILABLE 2026-08-04**: every call (model
  catalog and inference, retried across the session) returned HTTP **410 Gone** from
  `models.github.ai` — the endpoint itself, not a quota refusal. Per the standing rule (no
  unauthorized substitute reviewer, no self-grading in its place) the second seat is recorded
  as NOT OBTAINED; the #355 build PR should request the second review if the server is back.
