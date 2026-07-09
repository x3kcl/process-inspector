# Instance Migration — design (v0.1, panel-reviewed, PRE-BUILD)

> Status: **design draft**, reviewed by a 4-voice expert panel (Flowable-REST honesty,
> corrective-actions safety, operator-UX/product, and Gemini 2.5). Not yet merged into the
> lockstep docs. When the build is authorized this becomes the authoritative source for the
> migration feature and the SPEC §5 / ARCH §5 / IMPLEMENTATION-PLAN §474-480 edits land in the
> same change (spec-sync). Mirrors the doc-per-feature convention of `REGISTRY-CRUD.md` /
> `CMMN-SCOPE-PHASE-0.md`.

Feature: **migrate a running process instance to another deployed version of the SAME process
definition key** (SPEC §5 tier-3 "Migrate instance"). Move instances wedged on a bad-deploy
version *forward* without terminate+restart — which severs token position and history continuity.

---

## 0. The marquee property — and its honest limits
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

## 2. Flowable wire shape — SPIKE LIVE in P0, never assume
The single-instance shape below is a HYPOTHESIS to confirm on **6.5, a mid-6.x, and 7.1** before
any path string or DTO hardens (the spec flags "batch shape varies 6.5→7.x", ARCH §2.5):
- Execute: `POST /runtime/process-instances/{id}/migrate` — body = migration document.
- Validate: path is **NOT reliably** `…/migration/validate`. Flowable has historically split
  single-instance vs by-definition migration across different resources and moved them between
  6.x and 7.x. **Curl BOTH verbs independently in P0.** Treat `/migrate` and the validate path
  as two separately-unproven strings.
- Migration document (field name `activityMigrationMappings` — confirmed correct, NOT
  `activityMappings`). **The mapping DTO must model the richer forms**, even though slice-1's UI
  only drives one-to-one:
  ```json
  {
    "toProcessDefinitionId": "orderProcess:5:abc",
    "activityMigrationMappings": [
      { "fromActivityId": "reviewTask", "toActivityId": "reviewTaskV2" },
      { "fromActivityIds": ["a","b"], "toActivityId": "merged" },      // many-to-one
      { "fromActivityId": "split", "toActivityIds": ["x","y"] }        // one-to-many
    ]
  }
  ```
  A one-to-one-only DTO would make parallel-gateway / multi-instance renames unexpressible.
  (alt target selector: `toProcessDefinitionKey` + `toProcessDefinitionVersion` +
  `toProcessDefinitionTenantId` — thread tenant through both calls.)
- Validate response: `{ migrationValid: bool, validationMessages: [String] }` — confirm in P0.

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
- **P0 — spike (½–1 day):** live wire-shape on 6.5 / mid-6.x / 7.1; lock the two path strings +
  document + validation-response field names; `demoMigration` seed v1/v2; capability probe for
  the validate resource. Gate: green spike IT.
- **P1 — backend + on-ramp (2–3 days):** `MigrationService` (clone `FlowSurgeryService` rail
  order + the §5 CAS assertion); `FlowableEngineClient.migrateInstance` / `validateMigration`
  (tenant-threaded); richer mapping DTO; `ActionVerb.MIGRATE_INSTANCE("migrate-instance", 3,
  Role.ADMIN, INSTANCE)`; validate + execute endpoints; versions endpoint; capability +
  validate-gap gating; full guard + outcome + CAS ITs. spec-sync: SPEC §5 copy/badge, ARCH §5
  RBAC, ARCH §2.5 validate probe, IMPLEMENTATION-PLAN §474-480 (resolve the diagram-slice
  conflict), RUNBOOK recovery.
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
| Q3 | Cache validate | **Always live**, execute re-validates | unanimous |
| Q4 | Batch | **Defer entirely**; version the audit schema now; don't scaffold poll | unanimous |
| Q5 | Migrate-back | **Manual re-run**, no one-click (would fake an undo) | unanimous |
| Q6 | Validate trigger | **Explicit Validate click** (anchors audit digest, legible) | Gemini + Flowable-API vs UX |
| Q7 | RBAC | **Tier-3 ADMIN unconditional every env**; typed-confirm is the prod escalation | unanimous |
| — | Validate-gap engine | **Allow with UNCHECKED banner** + heightened confirm (not full refuse) | B (Gemini + Flowable-API); flagged for sign-off |
| — | On-ramp | **Definition-versions view into slice-1** (cohort visibility) | UX; unopposed |

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
