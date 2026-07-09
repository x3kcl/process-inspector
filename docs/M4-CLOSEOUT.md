# 🧾 M4 CLOSE-OUT — audit & operations finishers *(REVIEWED v1.0 — panel + Gemini, 2026-07-09)*

Design doc for the five remaining M4 items (IMPLEMENTATION-PLAN §M4 "Still open"). Normative
targets: SPEC §9 (audit + attribution), ARCHITECTURE §6, OPERATIONS §6, DATA-CLASSIFICATION
§2/§3, AUDIT-ATTRIBUTION.md. Register: R-AUD-03/07, R-SAFE-12, plus new **R-AUD-10**.

Reviewed by a five-seat panel (security/threat-model, data-protection, backend-architecture,
operations, spec-coherence) + Gemini adversarial. The review **changed the shape** of two
items — read §A before the workstreams. Nothing here is built; this is a design lock.

> **Honest scope correction (spec-coherence + backend seats).** The task framed item 5 as
> "provisioning, not schema." That is true for the *role grants* only. The panel found the
> audit table **has no monthly partitions today** (only `audit_entry_default`), so the whole
> retention story needs a partition **maintainer** first, and DB-enforced legal-hold + a
> constrained purge function are **schema** (Flyway), not provisioning. Item 5 is therefore
> larger than "run a SQL script." See §5 and the scoping question in §9.

> **Priority honesty (spec-coherence seat).** These five are the plan's "Still open in M4"
> list but they are **not all v1-gating**. MUST-v1: R-SAFE-12 reload-audit, the R-AUD-03
> sub-clauses (payload modes, retention, DB roles). SHOULD-v1.x: R-AUD-07 validate/linkify.
> Optional bonus (no MUST): X-Forwarded-User. §7 tags each row with its register priority.

---

## A. Two shape-changing resolutions (read first)

### A1 — Config-event failure policy is a **trichotomy**, not "fail-open" (security B2, DP, Gemini)

The DRAFT made all config events fail-**open**. The panel refuted this for the highest-value
event. A config event's failure policy depends on whether it records an **irreversible
external fact** or a **deliberate action we sequence**:

| Event | Policy | Why |
|---|---|---|
| `config-scope-mapping-reload` | **fail-to-previous** | The reload is the *privilege-grant* mechanism (a mid-incident grant goes live within the TTL). The BFF *chooses* when to honor a new mapping — it is not an unstoppable fact. If the audit event cannot be persisted, **keep serving the previous known-good mapping** and do not adopt the change. This preserves the D1a availability goal (auth checks never wedge on the audit DB — they keep using the old snapshot) while closing the *silent privilege-escalation* hole (adversary edits the mapping + induces an audit-insert failure → new grants live, no ledger row). Pure fail-open here was a security bug. |
| `audit-retention-purge` | **fail-closed ordering** | We control the order: write the audit event (with the chain checkpoint, §A2) **before** the DROP. If the audit write fails, the DROP does not run. Nothing is lost. |
| `audit-legal-hold-set` / `-release` | **fail-closed** | A deliberate governance action by a human; must never silently fail. |

So **nothing** in M4 close-out is truly fail-open. The `recordConfigEvent` primitive takes an
explicit `FailurePolicy` per call. Mutation audit (`beginPending`) stays **fail-closed** and is
never weakened — the three paths are asymmetric with mutations strictest.

### A2 — The retention purge is **BFF-orchestrated through a constrained DB function** (security B1/M7/M8/M9 + backend BLOCKER-1/MAJOR-4 + DP MAJOR-4 + Gemini)

The DRAFT's "Option A: external cron writes its own audit row" is **withdrawn** — it introduces
a *second writer* to a hash chain serialized by a process-local JVM lock (`chainLock` +
`findTopByOrderBySeqDesc`), which forks the chain (self-inflicted tamper-evidence failure). The
DRAFT's Option B (raw DROP inside the BFF) was already rejected on threat-model grounds. The
panel converged on a **third design** that beats both:

1. **Partition maintainer first (backend BLOCKER-1).** `audit_entry` currently has *only*
   `audit_entry_default` — there are no monthly partitions to drop. Add a
   `AuditPartitionMaintainer` (mirroring the existing `SnapshotPartitionMaintainer`, V5) that
   creates months ahead, plus a migration that carves the existing default rows into month
   partitions. **Without this, "drop old partitions" drops nothing or drops everything.** This
   is the prerequisite the DRAFT missed.
2. **A `SECURITY DEFINER` purge function** `purge_audit(cutoff)` owned by the table-owner role.
   It (a) **refuses any cutoff newer than `retention-days`** (a compromised caller cannot wipe
   recent audit), and (b) **refuses partitions overlapping an active `legal_hold` row**
   (hold enforced in the DB, not merely consulted by a cooperating job). It returns the dropped
   range's boundary metadata.
3. **The BFF orchestrates, single-writer.** A `@Scheduled` job on the BFF: writes the
   fail-closed `audit-retention-purge` config event **including the chain checkpoint** — the
   `chain_hash` + `seq` of the last dropped row and the first surviving row, so the chain stays
   cryptographically **stitched across the gap** and a maliciously-removed segment is
   detectable (resolves the "drop looks like retention" chain-severing finding). Then it calls
   `SELECT purge_audit(cutoff)`. The BFF connects as `inspector_app`, which has **`EXECUTE` on
   `purge_audit` only — never raw `DROP`/ownership**. So the chain has one writer (the BFF),
   DROP capability is *not* ambient in the most-attacked process, and holds/age are DB-enforced.

This satisfies the "keep DROP out of the app role" intent **and** the single-writer chain
invariant **and** DB-level hold enforcement simultaneously. `legal_hold` is a **table** (it
backs the DB-enforced check — a config file cannot); this is a justified Flyway touch, not the
scope creep the "provisioning, not schema" framing implied.

**Reconcile with shipped code (ops M1).** `SnapshotPartitionMaintainer` **already** runs
owner-`DROP`/`CREATE` on `triage_snapshot` from inside the BFF on the app datasource — so "no
DROP in the BFF" is not literally true today and must be scoped to the **audit** tables. The
coherent split: the app role **owns the operational tables** (`triage_snapshot` — non-sensitive,
regeneratable; `instance_note`; `protected_instance`) and may maintain their partitions
directly, but is **not** the owner of the audit objects (owned by a separate migration/owner
role; app gets `INSERT/SELECT` + column-`UPDATE` + `EXECUTE purge_audit` only). Audit is the
specially-protected table; the snapshot maintainer pattern is unchanged.

**Partial-purge atomicity (ops M5).** DDL auto-commits, so a "DROP several partitions then
INSERT the event" script can drop-then-die with no ledger row — violating "every purge is
audited." The BFF orchestrator therefore records a **started** config event, drops partitions
**one at a time** (each individually attributable — checkpoint per partition), then a terminal
event with the actual `{partitionsDropped}`. A partial failure still leaves an accurate ledger.

**Grant durability (ops M2).** Run-once grants do not cover tables created by *later*
migrations (a future `legal_hold`, the monthly child partitions). Use
`ALTER DEFAULT PRIVILEGES FOR ROLE <owner>` so Flyway-created objects auto-grant to the roles —
otherwise the app breaks on first use of any new audit object, which is exactly the
provisioning-rot the split is meant to avoid.

**App-role append-only, done properly (security M8):** `audit-roles.sql` must also
`REVOKE TRUNCATE` (TRUNCATE bypasses the DELETE guard trigger and is not covered by
`REVOKE DELETE`), and the BFF must connect as a **non-owner** role (an owner can disable the
guard trigger and rewrite history). The column-scoped `UPDATE (http_status, outcome,
response_snippet, response_truncated)` grant is correct and must stay in **one** source of
truth with the guard trigger's allow-list (asserted by a test — security M7-drift).

---

## 0. The spine: a config-event audit primitive (new — R-AUD-10)

Scope-mapping reload, retention purge, and legal-hold need to write a **fact that is not an
instance mutation**: no engine, no instance, no `PENDING → outcome` lifecycle. Today
`beginPending()` is the only write path and is instance-mutation shaped (`engine_id NOT NULL`,
two-phase, fail-closed).

**Decision D0.** Add a sibling write path `recordConfigEvent(verb, actor, payload, policy)` that
writes a **single-shot terminal row** — outcome inserted directly as `ok`/`failed`, no PENDING
phase. It shares the table, the `chainLock`, `chainHash`, and secret redaction. It differs:
- **Failure policy is per-call** (§A1: fail-to-previous | fail-closed-ordering | fail-closed) —
  not a blanket fail-open. Distinct metric `audit_config_event_failures_total`.
- **Reserved actor + engine namespace.** `actor = "system"` for observed facts; the human for
  deliberate actions (legal-hold). `engine_id = "_inspector"` sentinel (see D0a).
- **Implementation note (backend MINOR-7):** the `AuditEntry` constructor hard-codes
  `outcome = PENDING`; `recordConfigEvent` must set the terminal outcome in-memory
  (`close(...)` before the single `saveAndFlush`) so it is **one INSERT, no UPDATE** — the
  guard trigger fires on UPDATE/DELETE only, the outcome CHECK already permits `ok`/`failed` on
  INSERT, and `AuditPendingSweeper` (PENDING-only) leaves it alone. `chainHash()` dereferences
  `engineId` directly and NPEs on null — so the sentinel is **required**, not just tidy.
- **Statement timeout (backend MINOR-8):** the config-event INSERT shares `chainLock`;
  fail-*policy* catches an exception but not a *hang*. Put a short `statement_timeout` on the
  config-event insert so a slow audit DB during a reload cannot wedge `beginPending`.

**D0a — sentinel, with the invariant enforced and read-RBAC specified (security M5/M6, backend
MINOR-10).** Keep the `_inspector` sentinel (a discriminator column would be a change to the
golden-master table — heavier and riskier than the registry columns we already add). But the
sentinel is only as safe as its invariant:
- **Enforce at registry ingest:** reject any `engineId` beginning with `_` (fail-fast). Today
  there is **no such validation** — without it, an attacker who can register `_inspector`
  injects forged system-authored config events. This check is a condition of the sentinel.
- **Specify config-event read-RBAC:** `/audit` gates payload visibility by
  `hasRoleOn(user, OPERATOR, engineId)`; for `_inspector` that is true only for wildcard
  (`engine-id:"*"`) ADMINs — an accidental access-control change. **Decision:** config-event
  payloads are visible to **any ADMIN** (engine-scope-independent); the `/audit` grid renders
  the `_inspector` engine cell without resolving it through the registry (no 404). Tested.

New verbs: `config-scope-mapping-reload`, `audit-retention-purge`, `audit-legal-hold-set`,
`audit-legal-hold-release`, and (DP MAJOR-3) `config-audit-payload-mode-change`.

**D0b — R-AUD-10 scope (spec-coherence).** The *behaviours* (audit the reload, audit the
purge) are already required by R-SAFE-12 and R-AUD-03/DATA-CLASSIFICATION §3. R-AUD-10 is
narrowed to the **novel contract**: the fail-policy trichotomy + the `_inspector` sentinel +
config-event read-RBAC + the explicit **carve-out from R-TEST-03** ("missing audit row = Sev1")
for the fail-to-previous path (a *deliberately* absent row that kept the system safe is not a
Sev1 — but the metric must fire and alert).

---

## 1. Audit-row config events for scope-mapping reloads (R-SAFE-12)

**Today:** `ScopeMappingService.load()` logs the hash-change at INFO; **no audit row**.

**Decision D1.** On a content-hash change, `recordConfigEvent("config-scope-mapping-reload", …,
policy=FAIL_TO_PREVIOUS)` with payload `{file, sha256, previousSha256, groupCount, grantCount}`.
Failed reload → same verb, `outcome:"failed"`, payload `{file, errorClass, sanitizedMessage}`.
`actor="system"` (the TTL-tripping user did not cause the change).

- **D1a — emit outside the lock, adopt-only-if-audited.** The reload fires lazily under
  `synchronized(this)`. Emit the audit event **after releasing the lock** so audit-DB latency
  never couples to auth resolution. Under `FAIL_TO_PREVIOUS`, if the event cannot be persisted,
  **do not swap in the new snapshot** — keep the previous known-good mapping (§A1).
- **D1b — audit the boot baseline** (`previousSha256:null`) — a legitimate ledger anchor.
- **D1c — sanitize the error payload (DP MINOR-6 / security m1):** never store the raw YAML
  parse message (it echoes group/grant file fragments — org structure, near-PII). Exception
  class + a CR/LF-stripped, length-capped message only.

---

## 2. `X-Forwarded-User` engine attribution — send-side (SPEC §9 bonus, ARCH §6)

Optional, per-engine, **never-relied-upon** bonus: where **we** control the engine, the BFF
sends the acting human so an engine-side interceptor can write native Flowable attribution.
The BFF audit log stays the golden master. *(No MUST requirement gates this — it is design-only
against SPEC §9 / ARCH §6, not a claimed R-AUD-09 deliverable; spec-coherence MINOR.)*

- **D2 — registry `forward-user` flag, off by default, coupled to trust class (security m3).**
  New `engine_registry` column (a real V-migration + `EngineConfig`/`Row`/`Mapper` change +
  a DEFAULT for the already-seeded table — backend MAJOR-3). **Refuse (or hard-warn) at
  config-load** if `forward-user:true` on an engine classified external / outside the ARCH §6
  fence — reuse the same environment classification that drives §5.0 typed-token gating.
- **D2a — actor propagation, NOT `SecurityContextHolder` (backend MAJOR-2, security M2).** The
  header must carry the **same explicitly-propagated actor the audit row uses**. `BulkJobService`
  fans out on virtual threads and threads the actor as an explicit `Authentication` parameter
  precisely because thread-locals don't inherit — an interceptor reading `SecurityContextHolder`
  would be **empty on every bulk worker** (header silently dropped on bulk) or **stale** (wrong
  human). Invariant + test: forwarded `X-Forwarded-User` **== the audit row's `actor`** for that
  call. The interceptor goes on the **write** client and is fed the per-call actor via call
  context, never a bare thread-local.
- **D2b — sanitize + null-drop.** Strip CR/LF, cap length, drop the header if actor blank.
- **D2c — spoofing contract is normative (security M1).** Engines are directly reachable, so
  `X-Forwarded-User` is forgeable at the engine. The contract states: the header is trustworthy
  **only over an authenticated BFF→engine channel** (mTLS / hard network isolation), any
  conforming interceptor **MUST ignore inbound `X-Forwarded-User` from non-BFF sources**, and
  the BFF **scrubs any client-supplied inbound `X-Forwarded-User` at ingress** (the `prod-like`
  proxy runs with forwarded-headers-on) so it can never be reflected outward.
- **D2d — break-glass:** send `break-glass-<user>` (namespaced so it can't be confused with a
  real OIDC subject); honors per-engine opt-in. Note it signals "IdP down" to engine-side logs
  — acceptable only on genuinely-trusted engines, which off-by-default already implies (m5).
- **D2e — client-cache eviction (backend MAJOR-5).** In this checkout `FlowableEngineClient`
  caches clients with no evict path. Installing/removing the interceptor on a `forward-user`
  flip needs an eviction hook — a latent gap for *all* registry CRUD, surfaced here.
- **D2f — egress log hygiene (security m4).** The header is employee identity (PII) outside the
  §4 payload modes; identity-forwarding engines' egress path must not log request headers.

---

## 3. R-AUD-07 — `ticketId` validation + linkify + filter *(SHOULD-v1.x; webhook stays deferred)*

- **D3 — server-side validation in the guard chain.** Config `inspector.audit.ticket-id-pattern`
  (regex; default null = accept anything non-blank) + `ticket-required-on` (`none|prod|all`,
  default `none`). Malformed → refuse `ticket-id-invalid`; absent → refuse only where
  `ticket-required-on` demands, against the engine's environment classification (D3b). DB CHECK
  stays nullable; enforcement is BFF policy (matches the reason-ladder precedent).
- **D3a — linkify (client) + template validation (server).** `ticket-url-template` via
  `/api/meta`; SPA renders `template.replace("{ticketId}", encodeURIComponent(id))` as a link
  (`rel="noopener noreferrer"`), plain text otherwise. Validate the template with `URL()` —
  `protocol ∈ {http:, https:}`, exactly one `{ticketId}` — at config-load **and on every write
  if it ever becomes runtime-mutable** (security m2). React text/`href` binding, no
  `dangerouslySetInnerHTML`, the id is regex-constrained. Keep every layer.
- **D3c — the ticketId *filter* (spec-coherence MINOR).** R-AUD-07 also requires a ticketId
  filter on the `/audit` ops log (today only actor/action/since). S3 adds it, or §7 must not
  claim R-AUD-07 landed.
- **D3d — minimization (DP MINOR-7).** Even with pattern null, enforce a length cap + CR/LF
  strip on ticketId (it is chain-covered, unredacted, 400-day-retained); ship a conservative
  default pattern for prod; add ticketId to DATA-CLASSIFICATION §4's "don't paste PII" list.

---

## 4. Per-engine `audit-payload: full | redacted | metadata-only` (R-AUD-03)

**Today:** `redact()` applies only the secret-name denylist, uniformly, and **recurses into
Maps only — it is List-blind** (a live denylist bypass: `{"variables":[{"password":"x"}]}` is
stored in clear today). No per-engine mode.

- **D4 — mode from the registry (new column, real migration — backend MAJOR-3), applied at
  capture.** Default **`redacted`** (minimization by default; `full` is the deliberate opt-in
  per DATA-CLASSIFICATION §2). `full` = names+values, denylist still applies. `redacted` = keep
  the **whitelisted skeleton** (`name, scope, valueType, executionId, activityId, job ids`),
  mask value-bearing leaves → `«redacted»`. `metadata-only` = drop value-bearing entirely, keep
  the skeleton. `redacted`'s definition is pinned to "mask value-bearing leaves, preserve
  skeleton" (resolves the DRAFT's §D4-vs-D4b ambiguity, DP MINOR-9).
- **D4a — `AuditPayloadPolicy` value object, resolved at the call site.** `AuditService` stays
  engine-agnostic; the caller (which holds the engine id) resolves the policy and passes it in.
  A value object (not a long param list) avoids rippling a new parameter through **every**
  `beginPending` caller including the registry-seed path (which has no engine mode → default).
- **D4b — the value-key set must be per-verb, not a shared guess (backend MINOR-9).** The
  DRAFT's shared set `{oldValue,newValue,value,values,variables}` is **demonstrably incomplete**
  — real payloads carry `businessKey`, `exceptionMessage`, `expectedOldValue` as value-bearing.
  Enumerate value-bearing keys **per verb**; unknown keys **fail toward minimization** (treated
  value-bearing). Do **not** let the fallback mask skeleton coordinates.
- **D4c — recurse into Lists (DP MAJOR-2 / security M3).** Fix `redact()` and the mode transform
  to traverse `List`/`Collection` elements, not just Maps. Transform-matrix unit tests over
  list-valued payloads (bulk edit-variable) are a **correctness prerequisite** of S2.
- **D4d — the mode must also govern `response_snippet` (security M4).** The mode is applied to
  the *request* payload in `beginPending`, but the engine **response** is captured separately
  into `response_snippet` in `close()` with only a byte-cap — and Flowable echoes the variable
  **value** back. A `redacted`/`metadata-only` engine that scrubs `newValue` would still persist
  it in the snippet. The resolved policy must govern `close()`: store status + a redacted marker
  (or run the snippet through the same transform) for non-`full` engines. Without this the
  write-time-minimization claim (D4e) is false.
- **D4e — write-time minimization** (value never stored ⇒ not recoverable by later role
  escalation or a DB dump) is genuinely stronger than read-gating — *once D4c/D4d close the
  leak channels*.
- **D4f — auditing the flip (DP MAJOR-3).** A registry edit of `audit-payload` (esp.
  `redacted → full`) is the single most DP-relevant toggle in the system. Emit
  `config-audit-payload-mode-change` (actor = the human, old→new). Consider a DPIA-acknowledgment
  field required before `full` is accepted at config-load, tied to the R-GOV-04 onboarding ramp.
- **D4g — residual: variable *names* (DP MINOR-9 / security m6).** `redacted`/`metadata-only`
  keep `name`; data-derived names (`patientId`, `applicantEmail`) are residual PII. Document the
  schema-discipline expectation (names must not encode PII); optional name-hashing is a v-next.

---

## 5. Retention purge + DB role grants — see §A2 for the architecture

- **5a — roles (provisioning).** `deploy/sql/audit-roles.sql`. **Complete grant matrix (ops
  M1) — the DRAFT's audit-only list was incomplete:** `inspector_app` needs `INSERT, SELECT` +
  column-scoped `UPDATE` on the four audit outcome columns, **`USAGE` on `audit_entry_seq`**
  (without it the app cannot INSERT a single audit row — the sharpest omission), full DML +
  ownership on the operational tables it maintains (`triage_snapshot`, `instance_note`,
  `protected_instance`), write access to `legal_hold`, `EXECUTE` on `purge_audit`, and
  `REVOKE UPDATE, DELETE, TRUNCATE` on the audit tables; **non-owner of the audit objects**.
  `inspector_ops`/owner role owns the audit objects, the `SECURITY DEFINER` function, and reads
  `legal_hold`. **Two-phase ordering (ops M2):** roles created **before** the BFF connects;
  grants applied **after** `flyway migrate` (objects must exist); `ALTER DEFAULT PRIVILEGES` so
  later-migration objects auto-grant. **Idempotency (ops m1):** Postgres has no `CREATE ROLE IF
  NOT EXISTS` — use a `DO`/`pg_roles` guard; note that re-running does **not** rotate passwords
  (explicit `ALTER ROLE … PASSWORD` or out-of-band). **Honesty (ops M3):** `deploy/`, the
  `prod-like` compose profile, and the Testcontainers-Postgres suite **do not exist yet** — the
  "exercised before prod" claim is aspirational until they land. S5 must ship the `prod-like`
  profile *and* a **grant-level negative test** (app role's `DELETE`/non-outcome `UPDATE`
  refused by the *grant*, not merely the trigger; ops role can drop a partition; app cannot) or
  the split is untested and rots. On a fresh dev box that never runs the script the app still
  boots (trigger is the in-DB backstop) — a two-tier reality (dev = trigger-only, prod =
  trigger+grants) that only a gated `prod-like` run keeps honest.
- **5b — partition maintainer + purge function + orchestration:** §A2. Config
  `inspector.audit.retention-days` (default 400). Effective max retention ≈ 400 + partition
  width (DP MINOR-10) — state the real number in the processing record.
- **5c — legal hold:** a `legal_hold` table (engine/tenant/window), consulted **by the DB**
  inside `purge_audit`. Set/release = fail-closed config event, `actor` = the human. Whole-
  partition skip over-retains unrelated subjects by up to a partition width (DP MAJOR-5) —
  document the exposure in the processing record; row/instance-scoped enforcement is a v-next.
- **5d — operability (ops M4/M5/m3/m4/m5).** The purge is BFF-`@Scheduled`, so a *stopped*
  purge is visible, not a silent external cron. Ship real alert rules (the `deploy/` alert dir
  is referenced but absent today): a **dead-man's-switch on the absence of a recent
  `audit-retention-purge` event** (the earliest signal, long before disk>80%), a guard alert on
  **non-trivial `audit_entry_default` row count** (the "create-ahead is broken / purge is a
  no-op" signal — §A2), `audit_config_event_failures_total > 0` routed like
  `audit_insert_failures_total`, and counters for **failed scope-mapping reloads** and
  **ticket-validation refusals by reason** (a newly-tightened ticket policy blocking prod
  mutations is a self-inflicted-outage class you must be able to alert on). **Restore drill (ops
  m3):** role passwords + grants are cluster-global (`pg_authid`/ACL) — a logical `pg_dump` does
  **not** carry them; the quarterly drill must `pg_dumpall --globals` **and** re-run
  `audit-roles.sql`, then verify grant-level enforcement post-restore, not just that rows + the
  hash chain survived. Config events use `ts=now()` and land in the current month's partition —
  the create-ahead job must guarantee it exists first (§A2, ops m5).

---

## 6. Slice plan (revised)

Registry-schema-touching slices are **serialized** (backend MAJOR-6): S2 and S4 both add
`engine_registry` columns + `EngineConfig`/`Row`/`Mapper`, and would collide on the same
migration version. Explicit version allocation, one slice per migration.

**S0 pulled forward (ops slice-plan finding).** The role regime is the defense-in-depth the
whole doc is premised on; landing it *last* means S1–S4 ship (and could reach prod) with
`inspector_app` still able to `UPDATE/DELETE` at the grant level. Land **role scaffolding
first**: the `prod-like` compose profile + Testcontainers-Postgres gate + `audit-roles.sql`
(roles, `ALTER DEFAULT PRIVILEGES`, grant-level negative test) as **S0**, so every subsequent
slice is exercised under the real grant regime. S0 carries no app-behaviour change — pure
provisioning + test harness — and unblocks honest "tested pre-prod" claims for S1–S5.

- **S1 — config-event primitive (§0) + scope-mapping reload events (§1).** The spine.
  `recordConfigEvent` + `FailurePolicy` trichotomy, `_inspector` sentinel **+ the engineId
  leading-`_` ingest rejection + config-event read-RBAC + `/audit` grid tolerance**, statement
  timeout, `audit_config_event_failures_total`, R-AUD-10 (+ R-TEST-03 carve-out), fail-to-
  previous in `ScopeMappingService`. Testcontainers IT: changed mapping → one event, chain
  intact; failed reload → `failed` row, sanitized error; audit-DB down → **previous mapping
  retained** + metric ticks + auth still resolves.
- **S2 — per-engine `audit-payload` modes (§4).** `V8` `engine_registry.audit_payload` column
  (+ DEFAULT backfill) + `EngineConfig`/entity/mapper; `AuditPayloadPolicy`; **fix `redact()`
  List-recursion first**; mode transform (per-verb value-keys); **govern `response_snippet`**;
  `config-audit-payload-mode-change` on flip. Unit transform-matrix (full/redacted/metadata ×
  each verb, incl. list-valued); IT: a `redacted` engine stores no values in payload **or**
  snippet.
- **S3 — ticketId validation + linkify + filter (§3).** Config keys, guard-chain validation,
  `/api/meta` template (URL-validated), SPA linkify + `/audit` ticketId filter, length/CRLF cap.
  Vitest for linkify/escape; backend tests for required-on-prod + malformed refusal.
- **S4 — X-Forwarded-User send-side (§2).** `V9` `engine_registry.forward_user` column +
  entity/mapper; **explicit actor propagation** (not `SecurityContextHolder`) + the
  forwarded==audit-actor invariant test; trust-class refusal; ingress scrub; **client-cache
  eviction hook**. MockWebServer: header present iff flag on + actor present + sanitized, on
  both single-target and bulk paths.
- **S5 — retention purge machinery (§5, §A2).** *Sub-sliced* — it is not "mostly docs":
  **S5a** `V10` `AuditPartitionMaintainer` + the default-partition carve migration + the
  `audit_entry_default` guard alert (the create-ahead substrate — the hard part); **S5b** `V11`
  `legal_hold` + `SECURITY DEFINER purge_audit()` (age + hold enforced in-DB) + the BFF
  `@Scheduled` orchestrator with started/terminal per-partition chain-checkpointing + legal-hold
  set/release config events + the dead-man/observability alert rules. Role grants for the new
  objects fold back into S0's `audit-roles.sql` (+ `ALTER DEFAULT PRIVILEGES` already covers
  them). OPERATIONS §6 + DATA-CLASSIFICATION §3 + RUNBOOK updated here.

S0 first (grant regime under everything). S1 next (its primitive + read-RBAC + sentinel-invariant
underpin S5). S2/S3/S4 sequence on the registry-migration axis (S2 → S4) with S3 independent.
S5a/S5b last (depend on S1's config-event path). **Migration versions: S2=V8, S4=V9, S5a=V10,
S5b=V11** — allocate explicitly to avoid collision with any parallel Registry-CRUD / Migration
session (they run in a `git worktree`; coordinate the next free `Vn`).

## 7. Register + lockstep deltas — applied **per-slice on green merge**, not at design lock

(spec-coherence MAJOR: on lock these rows are **designed (S1–S5)**; each flips to **landed**
only on its slice's green-CI merge SHA.)

| Item | Register | Priority | Slice |
|---|---|---|---|
| Config-event primitive (fail-policy, sentinel, read-RBAC, R-TEST-03 carve-out) | **new R-AUD-10** | MUST-v1 (mechanism) | S1 |
| Scope-mapping reload → ledger | R-SAFE-12 | MUST-v1 | S1 |
| Per-engine `audit-payload` modes + flip-audit | R-AUD-03 | MUST-v1 | S2 |
| ticketId validate + linkify + filter | R-AUD-07 (webhook stays deferred) | SHOULD-v1.x | S3 |
| X-Forwarded-User send-side | design-only vs SPEC §9 / ARCH §6 (no MUST) | optional bonus | S4 |
| Retention purge + partitions + legal-hold + DB roles | R-AUD-03 | MUST-v1 | S5 |

Docs touched (each in the slice that lands it): **SPEC §9** (attribution — *not* §13, which is
the release gate; spec-coherence MINOR), **OPERATIONS §6**, **DATA-CLASSIFICATION §3/§4**,
**ARCHITECTURE §6**, **REQUIREMENTS-REGISTER**, **TRACEABILITY-MATRIX**, **TEST-SCENARIOS**,
**TEST-STRATEGY §11** (the audit-integrity suite is merge-gating from M4 — extend it with a
fail-to-previous / fail-closed-ordering proof), **RUNBOOK**.

## 8. Panel open-question resolutions

1. **D0a sentinel vs discriminator** → **sentinel**, conditioned on the enforced engineId
   invariant + specified config-event read-RBAC (S1). A discriminator would alter the golden-
   master table — heavier/riskier than the registry columns we already add.
2. **D1b boot baseline** → **audit it** (`previousSha256:null`).
3. **D2d break-glass forwarding** → **yes**, namespaced `break-glass-<user>`, per-engine opt-in.
4. **D4b value-keys** → **per-verb enumeration**, unknown → value-bearing.
5. **D5b purge runner** → **BFF-orchestrated via `SECURITY DEFINER purge_audit()`** (§A2); the
   external-cron option is withdrawn (second-writer chain fork).
6. **D5c legal-hold state** → **table** (backs the DB-enforced check).

## 9. The decision left for you (scope of the retention machinery)

The review turned item 5 from "run a provisioning script" into real engineering: a partition
maintainer + default-partition carve migration (S5a), a `SECURITY DEFINER` purge function +
`legal_hold` table + BFF orchestrator with chain-checkpointing (S5b), and the S0 role/prod-like
scaffolding underneath. That is sound but it is the **heaviest** work here and touches the
golden-master table's physical layout — whereas items 1–4 (config events, payload modes,
ticketId, X-Forwarded-User) are self-contained and independently valuable. How to sequence the
retention machinery is a genuine scoping call — posed in the accompanying message.
