# 🧭 REGISTRY CRUD — runtime engine lifecycle (design + panel, v2)

**Status:** ★ **BUILT 2026-07-09** — S1–S5 landed, CI-green; S4b (four-eyes on dangerous registry
writes + connect-time IP-pinning, issue #91) deferred (§10 below). Authoritative source-of-truth for
the Registry-CRUD feature — the WHAT/WHY/HOW/WHEN below drive the deltas into
`SPECIFICATION.md` (§4b), `ARCHITECTURE.md` (§3/§4/§5), `IMPLEMENTATION-PLAN.md` (v2 block)
and `REQUIREMENTS-REGISTER.md` (R-OPS-13 expanded; R-OPS-15, R-SAFE-13 added). This doc
deprecates the older one-line "registry CRUD (v2)" bullets it is referenced from.

**Discharges:** R-OPS-13 (the SSRF constraint text, now concrete + testable), R-OPS-15
(source-of-truth + hot reload), R-SAFE-13 (REGISTRY_ADMIN scope + governance), and folds in
R-SEM-17 (the "registry change = restart" statement is *superseded* for the CRUD-managed
path — see §4).

---

## 1. The problem & the benefit

Today engines exist only as the `inspector.engines` list in `application.yml`, bound and
`@Validated` at startup into `InspectorProperties` and snapshotted once, `enabled`-filtered,
into `EngineRegistry`'s `final LinkedHashMap`. Adding an engine, flipping one to read-only,
adjusting a per-engine `dlq-scan-cap` / alarm threshold, or rotating a `password-ref` all
require a **config edit + BFF redeploy**.

The benefit is **runtime engine lifecycle without a deploy**: ops onboards a
newly-stood-up prod engine, retires a decommissioned one, or tunes per-engine caps through
the UI. It is the natural completion of the "BFF is now stateful" arc — the registry moves
from YAML to a DB table, exactly as Saved Views + Recent Searches just did (v2/M4, PR #12).

The catch that keeps it gated to v2: **an engine entry is a base-URL the BFF will dial**, so
runtime CRUD opens an SSRF surface. The BFF is a credential vault (compromise = admin on
every registered engine, R-OPS-07); letting an admin point it at an arbitrary host is the
single most dangerous capability in the tool. **The guardrail work — host allow-listing,
resolve-then-pin DNS, a read-only-probe-before-trust ramp, and a distinct REGISTRY_ADMIN
scope — is most of the cost, and most of this document.**

---

## 2. Panel discussion (5 seats, independent deliberations → synthesis)

Method mirrors `DESIGN-REVIEW.md`: five seats deliberated independently against the real
M1/M4/v2 code, then conflicts were resolved. Seats: **security architect**, **lead
developer**, **DevOps/SRE**, **support-team lead** (the operator who will use it), **UX
expert**.

### Security architect (the load-bearing seat)
- The threat is not "a malicious admin" — it is a *coerced or mistaken* REGISTRY_ADMIN, an
  XSS/CSRF that rides their session, or a typo. Any of them turns the credential-vault BFF
  into an SSRF cannon aimed at cloud metadata (`169.254.169.254`), internal admin planes, or
  the Postgres host. **The base-URL field is the entire attack surface; treat every other
  field as trusted-by-comparison.**
- A per-request allowlist check is **not enough** — DNS rebinding defeats check-then-connect
  (validate `evil.com` → `1.2.3.4`, connect resolves `evil.com` → `169.254.169.254`).
  Demand **resolve-then-pin**: resolve the host once, validate every returned A/AAAA record,
  then connect to the *pinned literal IP* with the original `Host` header. The existing
  `followRedirects(NEVER)` is necessary but nowhere near sufficient.
- The allowlist must be **deploy config, not a UI field** — an admin who can edit the
  allowlist that bounds them has no allowlist. Registry rows live *inside* a fixed egress
  boundary set out-of-band (env/mounted file). This is the app-layer twin of the R-OPS-07
  network egress allowlist — defense in depth, not a replacement.
- New-engine trust is **earned, not asserted**: an added engine is born disabled +
  read-only, probed **read-only** (GET capability/version only — never a mutating call), and
  cannot be enabled read-write until a human confirms a successful probe. On prod, enabling
  read-write is a **four-eyes** action (R-SAFE-08).
- Registry CRUD **must be audited** with the same fail-closed rail as engine mutations — this
  is higher-privilege than any tier-3 verb. Before/after config in the payload; secret *refs*
  are names (safe) but run them through the existing denylist redactor anyway.

### Lead developer (cost & the real seams)
- Traced the wiring. Two seams, precisely: **(1)** `EngineRegistry` builds its map once in
  the ctor (`final`, `enabled`-filtered); every other consumer (`SearchService`,
  `TriageAggregationService`, `EngineHealthService`'s 30s loop, `MeController`,
  `RbacAuthorizer`, all the `require(id)` services) already re-reads `registry.all()` /
  `require(id)` **live per call** — so making the map reloadable updates the whole system for
  free. **(2)** `FlowableEngineClient` caches a `RestClient` per id **forever**
  (`readClients`/`writeClients` `computeIfAbsent`), and Resilience4j breaker/bulkhead
  instances materialize per name and linger. **Editing** an existing engine's
  base-URL/auth/timeouts is stale until those caches are evicted; **add** is free (lazy);
  **remove/rename** leaves orphan named instances.
- Therefore the whole reload story is: a `@Transactional` write → refresh
  `EngineRegistry`'s map → `FlowableEngineClient.evict(id)` → publish a `RegistryChangedEvent`
  → trigger an immediate health re-probe. No restart, no WebFlux, no new infra.
- **`id` is immutable, forever** — it is baked into composite IDs, audit rows, saved views,
  bulk-job items. "Rename" is not a verb; edit changes everything *except* `id`. This must be
  enforced, not merely documented.
- Reuse everything: the V6 saved-view shape (identity PK, `@Table` entity, `JpaRepository`,
  `@Transactional` store service, `ddl-auto=validate`), the fail-closed `AuditService`, the
  `@PreAuthorize` + `RbacAuthorizer` gate. The genuinely net-new code is the **SSRF
  validator** and the **reload/eviction plumbing**; everything else is precedent.
- Cost estimate: comparable to a v1.x fast-follow *plus* the SSRF validator (which needs its
  own hostile-input test corpus). Do it in slices; the validator lands and is tested **before**
  any UI can reach it.

### DevOps / SRE
- Hard constraint: **YAML must never stop working.** Air-gapped, DR-restore, and
  compose/k8s cold-start deploys rely on 12-factor config. A pure DB-authoritative registry
  with no seed is a bootstrap deadlock (empty table → zero engines → nothing to inspect).
- Resolution: **DB-authoritative *once initialized*, YAML as the one-time seed + a config-pin
  escape.** Empty engine table on boot ⇒ import `inspector.engines` as seed rows (audited as a
  system import), exactly like the one-time localStorage→server backfill. Non-empty ⇒ DB wins,
  YAML `inspector.engines` is ignored with a startup WARN naming the divergence. A
  `inspector.registry.source: db|config` switch lets a locked-down deploy **pin to config**
  (CRUD disabled, R-SEM-17 restart semantics restored) — the air-gap / "no runtime egress
  changes" posture.
- Secrets stay **env-ref only** (iron rule). The DB stores the `password-ref` *name*; the
  value never touches Postgres, the UI, logs, or audit. A ref whose env var is absent must be
  a **blocking, visible** pre-enable failure (reuse R-OPS-10's "credential rejected" vs
  "unreachable" split), never a silent 500 at first call.
- **Readiness contract (R-OPS-01) unchanged**: readiness = Postgres + registry *parsed/loaded*
  (now "DB rows loaded, or YAML seed applied"), never engine reachability. Registry rows being
  editable does not make an engine's reachability a readiness component.
- Wants: an `access-review`-style export of the effective registry (config-as-audited), and
  the drain/quiesce interaction stated — disabling an engine mid-bulk must pause dispatch to
  it (already the R-SEM-11 breaker-pause behavior; disable is one more pause trigger).

### Support-team lead (the 3am operator)
- The whole point is onboarding without a ticket-to-deploy round trip. But an operator will
  **not** read a threat model — the guardrails have to be *legible in the flow*, not a wall of
  "denied". A rejected base-URL must say **which rule** ("host is a private/internal address —
  register it on a dev-scoped inspector, or add it to the egress allowlist") with the next
  move, per the R-UXQ-05 message style.
- The **DRAFT → PROBED → ACTIVE** ramp is a feature, not friction: "Test connection" that runs
  the read-only probe and shows engine version + capabilities *before* anything is enabled is
  exactly the confidence an operator wants before flipping a prod engine live. Reuses the
  existing health-strip capability data.
- Editing caps (`dlq-scan-cap`, alarm thresholds, timeouts, page size) is the boring 80% and
  should be **low-friction** (OPERATOR-adjacent? — resolved *no*, see conflicts). Adding a URL
  or enabling read-write is the dangerous 20% and should carry the full ladder.
- Do **not** hard-delete an engine that has audit/notes/saved-view history — the "removed
  engine `<id>`" honesty (R-SEM-17) must survive. Disable/tombstone, purge later.

### UX expert
- New admin surface, its own route (`/admin/engines`), **greyed-never-hidden** for non-admins
  with the reason ("requires REGISTRY_ADMIN") per R-UXQ — not absent from the nav.
- The list shows lifecycle state as a first-class column with shape+label (DRAFT / PROBING /
  PROBE-FAILED / ACTIVE / DISABLED / REMOVED), env color band per R-UXQ-01, and the secret-ref
  **presence** indicator ("`ENGINE_X_PASSWORD`: present ✓ / not in environment ✗") — the ref
  *name*, never a value or a masked-dots pretense of a value.
- The add/edit form restates blast radius in the confirm (env band, "this engine will be
  dialed by the BFF from the server network"); prod enable-read-write gets the typed-token +
  four-eyes treatment already spec'd for tier-3. Enter never submits the dangerous confirms.
- Zero-states: no-engines (the first-run setup guide, R-UXQ-04 state 1) becomes *actionable*
  here — "Add your first engine". The config-pinned deploy shows a read-only registry with
  "CRUD disabled (registry source = config)".

### Conflicts resolved
- **DB-authoritative vs config-first-overlay** (lead dev leaned overlay for simplicity;
  DevOps + security wanted one unambiguous source): **DB-authoritative-once-initialized with
  YAML seed + config-pin** adopted. One code path (`EngineRegistry` loads from the store),
  no "which layer wins" ambiguity per row, YAML still boots a cold/air-gapped system, and the
  pin restores full config-only operation. Matches the saved-views precedent exactly.
- **Who may edit caps** (support lead wanted OPERATOR to tune `dlq-scan-cap`; security wanted
  everything REGISTRY_ADMIN): **all registry writes are REGISTRY_ADMIN.** A `dlq-scan-cap` is
  a do-no-harm knob that governs how hard the BFF scans an engine — not operator-trivial, and
  splitting the RBAC surface doubles the audit/guard matrix for little gain. Tuning is rare;
  keep one door.
- **Hard vs soft delete** (lead dev noted audit already tolerates unknown ids → "removed
  engine <id>"): **soft-delete/tombstone** adopted — DELETE requires the engine be disabled
  first, then sets a `removed_at` tombstone; the row leaves `EngineRegistry.all()` but stays
  for id→name resolution in audit/notes/saved-views. A separate REGISTRY_ADMIN purge (after a
  retention window) hard-removes.
- **Is REGISTRY_ADMIN a ladder rung or an orthogonal scope** (it is *fleet*-level; you cannot
  scope "add an engine" to an engine that does not exist): **orthogonal named grant**, not a
  rung. ADMIN-on-`orders-prod` does **not** confer REGISTRY_ADMIN. It maps from a dedicated
  OIDC group and is checked by a new `rbac.canAdministerRegistry(auth)` gate.

### Sixth seat — adversarial security review (independent, 2026-07-09)
An external adversarial pass stress-tested the locked design specifically for SSRF bypasses,
reload races, and "quiet lies" (a guardrail that fails open silently). Findings adopted
(they harden §4/§5/§9 below — the design changed, not just the prose):
- **Pin lifetime vs connect-time re-check was contradictory** ("cached forever" *and*
  "re-validate at connection time"). Resolved: the client pins the *validated literal IP*
  with a **bounded TTL**; connect-time re-validates the **pinned IP** against policy (cheap,
  **no re-resolve** — re-resolving at connect would reopen the rebinding window it closes);
  TTL expiry or a registry edit forces a rebuild that re-resolves + re-validates. See §5.
- **Reload must be strictly post-commit.** The original order (persist → evict → close audit)
  could leave in-memory state + client caches *ahead of* a rolled-back DB row (audit-close or
  commit failure). Resolved: all in-memory refresh / cache eviction / `RegistryChangedEvent` /
  re-probe run in a `TransactionSynchronization.afterCommit` hook — never before the row +
  audit commit. See §4.
- **The sibling-context URLs are an unguarded SSRF hole.** `FlowableEngineClient` string-munges
  `base-url` into `/external-job-api` and `/cmmn-api` with no validation today. Resolved: those
  derived URLs inherit the **same pinned-IP + policy** as the base client; base-URL is
  **canonicalized before validation** (trailing-dot, punycode, `..`/`.` path traversal,
  implicit-port normalization) so a `…/admin/../` or trailing-dot bypass can't sneak past. See §5.
- **The denylist must be IPv6-complete** (`::1`, `fe80::/10`, `fc00::/7`, and the
  `::ffff:169.254.169.254` v4-mapped metadata form), not just the v4 ranges. See §5.
- **The probe is an oracle** if failures are reported precisely to the UI (connection-refused
  vs timeout leaks internal topology). Resolved: **validation**-time rejections are specific
  (nothing was dialed — no oracle), but **probe**-time failures are coarse to the UI
  ("engine unreachable") with the raw exception TEXT in the audit row's `response_snippet`
  only; a fixed short probe timeout bounds timing leakage. Probes use the **fixed,
  known-idempotent** capability GETs (never an admin-chosen path — that would itself be an
  oracle), with backoff on repeated failure. See §5/§6. — **Amended #275**: "coarse" was
  read by testers as "generic to the point of unreadable" — every probe failure (nothing
  listening, a missing secret ref, a rejected credential, a genuinely-answering-but-wrong
  engine) rendered as the SAME line. The line the oracle argument actually draws is at
  exception *message* vs exception *TYPE*: a small closed set of failure-class buckets
  (`unreachable`/`missing_secret_ref`/`auth_rejected`/`unexpected_response`/`ssrf_rejected`,
  `ProbeFailureClassifier`) is derived from the exception's TYPE only, persisted on the row,
  and rendered as VISIBLE text — never the message, hostname, port, or stack trace, so the
  oracle property still holds.
- **Resilience4j instances must be *removed*, not reset, on tombstone/purge** — a reset leaves
  the named instance in the registry (memory creep on churn). The ≤20-engine cap (R-NFR-01)
  bounds it, and CRUD writes are rate-limited. See §9.
- **Drift must not be silent** (tempered — "block startup on YAML≠DB" was rejected: it would
  fail every deploy once the DB is seeded, defeating the whole feature). Resolved: DB stays
  authoritative, but the ignored-YAML case logs a **per-engine drift report** (not a generic
  WARN) and surfaces a **drift indicator** in the admin UI (`GET …/drift`). See §4.

**Cross-seat consensus:** trust is earned by a read-only probe, never asserted · the egress
allowlist bounds the admin, so it is deploy config not a UI field · resolve-then-pin (and
re-check the *pinned* IP at connect, never re-resolve) or it is not SSRF-safe · every derived
sibling URL inherits the base client's pin · reload is strictly post-commit · YAML never stops
booting the system but drift is never silent · `id` is immutable forever · registry CRUD is
audited fail-closed like a tier-3 verb · secrets stay env-refs, and ref-absence is a loud
pre-enable failure.

---

## 3. Boundary decisions (locked before build)

Mirrors the v2/M4 state-store boundary block — decided up front so the milestone doesn't
re-litigate them.

1. **Source of truth = DB, once initialized; YAML = one-time seed + config-pin.** Empty
   engine table ⇒ import `inspector.engines` (audited system import). Non-empty ⇒ DB wins,
   YAML ignored with a startup WARN. `inspector.registry.source: db|config` pins to
   config-only (CRUD off, restart semantics restored). Flyway owns the schema
   (`V7__engine_registry.sql`); `ddl-auto=validate` holds.
2. **The egress allowlist is deploy config, never editable in-app.** `inspector.registry.
   egress-allowlist` — a list of allowed host patterns and/or CIDRs the BFF may *ever* dial.
   A registered base-URL must resolve *inside* it. Dev/test deploys may include private ranges
   / `localhost`; prod is a strict list set out-of-band. This is the app-layer companion to
   the R-OPS-07 network egress allowlist.
3. **Validation is resolve-then-pin, not check-then-connect.** The SSRF validator resolves the
   host, rejects if *any* resolved address is loopback / link-local / private / CGNAT /
   metadata (unless the allowlist explicitly permits it for a dev engine), then the actual
   connection is pinned to a validated literal IP with the original `Host` header — closing
   the DNS-rebinding TOCTOU. `followRedirects(NEVER)` stays.
4. **Trust is a state machine, earned by a read-only probe.** DRAFT → (read-only probe) →
   PROBED | PROBE_FAILED → (human confirm; prod = four-eyes) → ACTIVE → DISABLED → REMOVED
   (tombstone). No mutating call is ever made to an engine that has not reached ACTIVE.
5. **REGISTRY_ADMIN is an orthogonal fleet grant**, distinct from per-engine ADMIN, mapped
   from its own OIDC group, checked by a dedicated gate. It is the highest-privilege surface
   in the tool.
6. **Registry CRUD is audited fail-closed**, reusing `audit_entry` (synthetic `instance_id` =
   the engine id, `action` = `registry-*`, payload = before/after config, secret refs
   redacted-by-name). Fail-closed: audit INSERT fails ⇒ the registry write does not happen.
7. **Hot reload, no restart.** A write refreshes `EngineRegistry`'s map, evicts the affected
   `FlowableEngineClient` caches (+ resets the R4j named instances on remove), publishes
   `RegistryChangedEvent`, and triggers an immediate re-probe. Config-pinned deploys keep the
   old restart-required semantics.
8. **`id` is immutable; delete is soft.** Edit changes everything except `id`. DELETE requires
   prior disable, writes a tombstone (id→name survives for historical references), and a
   separate purge hard-removes after retention.

---

## 4. Source-of-truth & reload mechanics

```
boot:
  if inspector.registry.source == config:            # air-gap / locked-down posture
      EngineRegistry loads from InspectorProperties (today's path); CRUD endpoints 403
  else (db):
      if engine table empty:  import inspector.engines as seed rows  (audit: registry-seed)
      EngineRegistry loads from the store (DB rows, tombstones excluded, enabled unfiltered*)

write (add|edit|disable|enable|remove|purge), REGISTRY_ADMIN, audited fail-closed:
  audit.beginPending (fail-closed) → validate (SSRF + slug + duplicate-id + secret-ref-present)
     → persist row (@Transactional)  ┐
     → audit.close(ok)               ┘ ONE transaction (row + audit outcome commit together)
     → afterCommit {                   # TransactionSynchronization — strictly POST-commit, so
         EngineRegistry.refresh(id)    #   in-memory state can never run ahead of a rolled-back row
         FlowableEngineClient.evict(id)   # drop cached RestClients; on remove/purge REMOVE (not
                                          #   reset) the R4j breaker+bulkhead named instances
         publish RegistryChangedEvent  # health service / samplers pick up next cycle
         trigger immediate read-only re-probe
       }
```
CRUD writes are **rate-limited** (a churn of add/remove can't leak client/R4j instances; the
≤20-engine cap, R-NFR-01, is the hard bound). If `afterCommit` reload partially fails (e.g. the
re-probe errors) the row is already the source of truth — the next `EngineHealthService` cycle
(30 s) reconciles the map, and the failure is logged, never a rollback of a committed edit.
*`EngineRegistry` keeps holding disabled rows now (for id→name resolution) but continues to
expose only `enabled` engines through `all()`/fan-out — the `enabled` filter moves from the
ctor to the `all()` accessor.

**Drift is never silent.** When the DB is non-empty and `inspector.engines` YAML is also
present, DB stays authoritative but boot logs a **per-engine drift report** (added/removed/
changed vs the YAML), not a generic WARN, and `GET /api/admin/engines/drift` + an admin-UI
badge surface it at runtime — so an operator who edits `prod.yaml` expecting effect sees the
no-op, instead of a silent divergence. (Blocking startup on YAML≠DB was rejected: it would
fail every deploy once the table is seeded.)

**Superseded:** R-SEM-17 ("Registry change = restart, stated") holds only for the
`source: config` posture. Under `source: db` the reload is live; the `POST /api/admin/drain`
affordance stays relevant for *code* deploys, not registry edits.

---

## 5. SSRF threat model & the guard ladder (R-OPS-13, concrete)

The base-URL is the whole surface. The validator (`RegistryUrlValidator`) runs on **every**
add/edit before persistence AND is re-asserted at connection time (belt + braces):

| Rail | Rule | Failure copy (R-UXQ-05) |
|---|---|---|
| **Canonicalize first** | Normalize *before* any check: lowercase host, strip trailing dot, decode punycode, resolve `..`/`.` path traversal + collapse slashes, make the port explicit (80/443). All later rails run on the canonical form. | "base-URL is malformed" |
| **Scheme** | `http` or `https` only. `https` required on `environment: prod`. | "prod engines must use https" |
| **Egress allowlist** | Host must match `inspector.registry.egress-allowlist` (host globs / CIDRs). Not editable in-app. | "host not in the egress allowlist — add it out-of-band or use a dev-scoped inspector" |
| **Address denylist (v4 + v6)** | Reject if the host or *any* resolved IP is loopback (`127/8`, `::1`), link-local (**incl. `169.254.169.254` + v6 `fe80::/10`**), private (RFC1918, `fc00::/7` ULA), CGNAT (`100.64/10`), the v4-mapped metadata form `::ffff:169.254.169.254`, multicast, or `0.0.0.0`/`::` — unless the allowlist explicitly permits it for a **dev** engine. | "host resolves to a private/internal address" |
| **Resolve-then-pin** | Resolve once; validate all A/AAAA; **pin the validated literal IP**; connect to the pinned IP with the original `Host`. At connect, re-check the **pinned IP** against policy — **never re-resolve** (re-resolving at connect reopens the rebinding window). A registry write (add/edit/probe) or reload rebuilds (re-resolve + re-validate) — **✅ SHIPPED S4b (#91)** via a JEP 418 `InetAddressResolverProvider` (`PinnedAddressResolverProvider`) that intercepts ONLY hostnames `RegistryPinRegistry` has pinned; every other hostname the JVM dials (Postgres, the OIDC issuer, …) is untouched. Event-driven, not a timer TTL: a pin is re-established at add/edit/probe and at boot + every registry reload, never on an ordinary health-loop tick or operation dial. | (internal — closes DNS rebinding) |
| **Sibling-context URLs** | The `/external-job-api` and `/cmmn-api` URLs derived by string-munging `base-url` inherit the **same canonicalization + pinned IP + policy** — they are not a validation gap. | (internal) |
| **No redirects** | `followRedirects(NEVER)` (already in `build()`); a 3xx from an engine base-URL is an error, never a hop. | "engine returned a redirect — base-URL misconfigured" |
| **Credentials in URL** | Reject `user:pass@host` embedded credentials; auth is the `Auth` record only. | "put credentials in auth, not the URL" |
| **Port** | Optional allowlist of ports per deploy; default allow any TCP port inside an allowlisted host. | "port not permitted" |
| **Secret ref present** | The `password-ref`/`token-ref` env var must exist before enable; absence blocks ACTIVE. | "secret env var `ENGINE_X_PASSWORD` is not present in this deployment" |

**Validation-time rejections are specific** (nothing was dialed — the rule name is safe copy);
**probe/connect-time failures are coarse** to the UI (a small closed set of failure-CLASS
buckets, #275 — see §2's "the probe is an oracle" amendment) with the raw exception message in
the audit row's `response_snippet` only, so the probe can't be used as an internal-topology
oracle (connection-refused vs timeout). A fixed short probe timeout bounds timing leakage.
Optional hardening: pin the BFF's `HttpClient` to a trusted resolver so the system resolver
can't map an internal hostname behind the validator's back.

The validator ships with a **hostile-input corpus** (metadata IP in many encodings — decimal,
octal, IPv6-mapped/`::ffff:`, trailing-dot, uppercase-hex; `..`-traversal + trailing-dot host;
rebinding stub; `file://`/`gopher://`; credential-in-URL; over-long host) as a CI-gating unit
suite — the SSRF twin of the R-OPS-08 hostile-message fixture. A quiet allow of any case = Sev1
(R-TEST-03).

---

## 6. Lifecycle state machine

```
        add (DRAFT)
           │  read-only probe (GET version + capabilities; NEVER a mutating call)
           ▼
   ┌── PROBED ───┐         PROBE_FAILED ──(fix + re-probe)──► PROBED
   │  confirm     │              │ (shown with the engine's own words, R-OPS-10 401 vs unreachable)
   │  prod⇒4-eyes │
   ▼              │
 ACTIVE ◄─────────┘   (enabled; read-write only after an explicit read-write enable, prod⇒token+4-eyes)
   │  disable (pauses dispatch, R-SEM-11)
   ▼
 DISABLED  ──enable──► PROBED/ACTIVE
   │  remove (requires DISABLED)
   ▼
 REMOVED (tombstone: id→name survives; excluded from all()/fan-out)  ──purge (after retention)──► gone
```

An engine never receives a mutating verb before ACTIVE, and read-write mode is a *second*
gated flip after ACTIVE (a newly-onboarded prod engine sits ACTIVE + READ_ONLY through its
pilot per R-GOV-04). Enabling read-write on a `prod` engine is four-eyes (R-SAFE-08) + typed
token (the engine id).

---

## 7. RBAC & governance

- **New grant `REGISTRY_ADMIN`** — a fleet-level authority (`ROLE_REGISTRY_ADMIN` in the dev
  chain; a dedicated OIDC group in prod), **orthogonal** to the VIEWER→ADMIN ladder. Checked
  by `rbac.canAdministerRegistry(authentication)`. Per-engine ADMIN does **not** confer it.
- **Every registry write** is gated `@PreAuthorize("@rbac.canAdministerRegistry(authentication)")`
  at the door **and** re-checked in the service — the first plain fleet-scoped gate in the
  codebase (closest mechanic: `ViewsController`'s `atLeast`).
- **Governance ladder**: read (list) = REGISTRY_ADMIN; cap/threshold edit = REGISTRY_ADMIN +
  reason ≥10; add + probe = REGISTRY_ADMIN + reason; **enable read-write on prod, remove, and
  purge** = REGISTRY_ADMIN + typed token (engine id) + four-eyes — **✅ SHIPPED S4b (#91)**: a
  `PENDING_APPROVAL` proposal (`registry_write_proposal`, V16) a SECOND independent REGISTRY_ADMIN
  (proposer ≠ approver AND the approver's REGISTRY_ADMIN group(s) disjoint from the proposer's)
  must approve via `POST /api/admin/engines/proposals/{id}/approve` before it applies; remove still
  requires disabled-first, purge still requires removed-first. A non-prod enable (any mode) and a
  prod enable staying read-only remain single-actor, matching the pre-S4b typed-token scope exactly.
- **Audit** every write through the fail-closed `AuditService` — `action ∈ {registry-seed,
  registry-add, registry-edit, registry-probe, registry-enable, registry-disable,
  registry-remove, registry-purge}`, `instance_id` = engine id, `payload` = before/after
  config (secret refs run through the denylist redactor), `outcome` PENDING→ok|failed. This is
  the same change-audit doctrine R-GOV-06 already mandates for the group→scope mapping CRUD.
- **Break-glass** (R-SAFE-11) grants ADMIN-global but **not** REGISTRY_ADMIN — an IdP outage
  is not a reason to repoint the credential vault. Registry CRUD is unavailable under
  break-glass (stated, greyed-with-reason).

---

## 8. Secret handling (iron rule preserved)

The DB stores the `password-ref` / `token-ref` **env-var name**, never a value — identical to
today's YAML. The UI shows the ref name plus a **presence** check (`Environment.getProperty`
non-null) so an operator sees "`ENGINE_X_PASSWORD`: present ✓" or "✗ not in this deployment"
*before* enabling — turning the R-OPS-10 "credential rejected" surprise into a pre-flight.
Rotating a secret **value** is still an out-of-band env change (no registry write needed;
picked up live because `resolveSecret` reads `Environment` per request). Rotating the *ref
name* is a registry edit that evicts the cached client (seam #2). No secret ever enters
Postgres, logs, audit payloads, the OpenAPI schema, or `EngineDto`.

---

## 9. API surface (ARCH §4 additions)

| Endpoint | Purpose |
|---|---|
| `GET  /api/admin/engines` | Full registry incl. disabled/draft/tombstoned rows + lifecycle state + secret-ref *presence* (never values). REGISTRY_ADMIN. Distinct from the secret-free, enabled-only `GET /api/engines`. Every row's `AdminEngineDto` also carries `lastProbeFailureClass` (#275 — set only while `lifecycle=probe_failed`, one of `unreachable`/`missing_secret_ref`/`auth_rejected`/`unexpected_response`/`ssrf_rejected`, cleared the moment a later probe succeeds) and `reachableNow` (set only for `lifecycle=active`, mirroring the live `EngineHealthService` result off `EngineRegistry#healthOf`; `null` until that engine has been checked at least once — never fabricated true/false). |
| `POST /api/admin/engines` | Add (→ DRAFT). Body = the registry config sans secrets (refs by name). SSRF-validated, slug + duplicate-id checked, reason ≥10, audited. |
| `PUT  /api/admin/engines/{id}` | Edit everything except `id`; re-validates, evicts client cache, re-probes. Audited before/after. |
| `POST /api/admin/engines/{id}/probe` | Run the **read-only** probe now (version + capabilities); returns the result, transitions DRAFT→PROBED / PROBE_FAILED. Touches the engine with GETs only. |
| `POST /api/admin/engines/{id}/enable` · `/disable` | Enable (→ ACTIVE) / disable (pause dispatch). `readWrite: true` on prod ⇒ typed token + four-eyes — returns `EngineWriteOutcome {status: applied\|proposed, …}`, not the bare engine DTO. Audited. |
| `DELETE /api/admin/engines/{id}` | Soft-delete (requires DISABLED) → tombstone. Typed token + four-eyes (ALWAYS, any environment). Returns `EngineWriteOutcome`. Audited. |
| `POST /api/admin/engines/{id}/purge` | Hard-remove a tombstone after retention. Typed token + four-eyes (ALWAYS, any environment). Returns `EngineWriteOutcome`. Audited. On remove/purge the R4j breaker+bulkhead named instances are **removed** (`registry.remove(name)`), not merely reset — a reset leaks the instance on churn. |
| `GET  /api/admin/engines/proposals` | The pending four-eyes inbox (`EngineProposalView[]`). REGISTRY_ADMIN. |
| `POST /api/admin/engines/proposals/{id}/approve` | A second independent REGISTRY_ADMIN approves a pending proposal, which then applies. Returns `EngineWriteOutcome`. Audited (on apply). |
| `GET  /api/admin/engines/drift` | The DB-vs-YAML drift report (added/removed/changed engines when `inspector.engines` is present but ignored) — feeds the admin-UI drift badge so an ignored `prod.yaml` edit is visible, never silent. REGISTRY_ADMIN. |

`GET /api/me` gains a `registryAdmin: boolean` hint so the SPA greys the admin nav correctly.
No generic proxy route; the path whitelist is unchanged.

---

## 10. Data model (`V7__engine_registry.sql`)

Follows the V6 shape (identity PK, `@Table` entity, `JpaRepository`, `@Transactional` store,
`ddl-auto=validate`). Not per-user-owned — global/fleet, gated by REGISTRY_ADMIN.

```sql
CREATE TABLE engine_registry (
    id            text PRIMARY KEY
                    CHECK (id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),   -- ENGINE_ID_PATTERN, immutable
    name          text NOT NULL,
    base_url      text NOT NULL,
    environment   text NOT NULL CHECK (environment IN ('dev','test','prod')),
    mode          text NOT NULL DEFAULT 'read-only'
                    CHECK (mode IN ('read-write','read-only')),
    lifecycle     text NOT NULL DEFAULT 'draft'
                    CHECK (lifecycle IN ('draft','probed','probe_failed','active','disabled','removed')),
    accent_color  text,
    tenant_id     text,
    telemetry_url_template text,
    auth_type     text NOT NULL CHECK (auth_type IN ('basic','bearer','none')),
    auth_username text,
    password_ref  text,          -- env-var NAME only, never a secret value
    token_ref     text,          -- env-var NAME only
    -- do-no-harm knobs (nullable → code defaults):
    connect_ms int, read_ms int, write_ms int,
    max_page_size int, dlq_scan_cap int,
    alarm_oldest_warn_min int, alarm_oldest_crit_min int, alarm_overdue_grace_s int,
    -- provenance:
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    removed_at  timestamptz,     -- tombstone; NULL = live
    source      text NOT NULL DEFAULT 'ui' CHECK (source IN ('ui','yaml-seed'))
);
CREATE INDEX idx_engine_registry_live ON engine_registry (lifecycle) WHERE removed_at IS NULL;
```
`advisories` / `require-second-approval` / `jurisdiction` are deferred until their features build
(they are absent from `EngineConfig` today too) — additive columns later, never a lie now.
`forward-user-header` shipped since (M4-CLOSEOUT §2/S4): `V9__engine_forward_user.sql` adds
`forward_user boolean NOT NULL DEFAULT false`, live end-to-end (`EngineConfig.forwardUser` →
`EngineRegistryRow`/`EngineRegistryMapper` → `InboundForwardedUserFilter`/`ForwardedActor` send-side).

---

## 11. Frontend (SPEC §4b)

- Route `/admin/engines`, REGISTRY_ADMIN-gated, greyed-never-hidden in the nav with the
  reason for others.
- **List**: env-banded rows, lifecycle column (shape + label per R-UXQ-01), secret-ref
  presence, health echo, "Test connection" (read-only probe) inline. A `probe_failed` row
  shows its failure-CLASS remediation text INLINE (#275 — "missing credential", "rejected
  before dialing", …, not just a bare "▲ Probe failed"); the raw connect exception stays a
  `title` tooltip pointer at the audit trail's `response_snippet`, never claiming a UI path
  to it that doesn't exist (the Operations Log grid doesn't render that field). An `active`
  row shows an explicit positive `reachableNow` badge sourced from the same live
  `EngineHealthService` result the header strip uses — distinct from "health pending" (not
  yet checked) and from a `disabled` row's policy-state badge, so "not failing" is never
  confused with "confirmed healthy just now".
- **Add/Edit form**: typed fields matching `EngineConfig`; live SSRF validation with the
  rule-named failure copy; secret entered as a **ref name** with a present/absent indicator;
  env band on the confirm; the prod enable-read-write path carries the typed-token + four-eyes
  UI already built for tier-3.
- **Zero-states** (R-UXQ-04): no-engines → "Add your first engine"; config-pinned →
  read-only registry with "CRUD disabled (registry source = config)".
- Types flow through `npm run gen:api` from the running BFF (never hand-written), same as
  every other surface.

---

## 12. Implementation slices → see `IMPLEMENTATION-PLAN.md` (v2 Registry-CRUD block)

Ordered so the dangerous plumbing lands and is tested behind nothing before any UI reaches it:
**S1** SSRF validator + hostile corpus (pure, no wiring) · **S2** V7 + entity/repo/store +
YAML-seed import + `source` switch · **S3** reload seam (`EngineRegistry.refresh` +
`FlowableEngineClient.evict` + event + re-probe) · **S4** REGISTRY_ADMIN gate + admin API +
fail-closed audit + typed-token on prod · **S5** admin UI + zero-states + gen:api · **S4b**
four-eyes dual-control (`PENDING_APPROVAL` proposals) + connect-time IP-pinning (JEP 418
resolver SPI), closing both S4 deferrals. Each slice: rung-1 unit → rung-3 Spring wiring/RBAC →
rung-4 dockerized-engine IT (probe/edit against real flowable-rest, both profiles) → Playwright
smoke.

## 13. Test strategy (highlights)

- **SSRF unit corpus** (S1, CI-gating): every metadata-IP encoding, rebinding stub,
  scheme/credential/redirect rejections — a quiet allow = Sev1 (R-TEST-03 "guard bypass").
- **Reload IT**: edit an ACTIVE engine's base-URL over REST → old cached client evicted, next
  call hits the new host (no `Thread.sleep`; Awaitility on the health re-probe).
- **RBAC matrix**: REGISTRY_ADMIN required at the door and in the service; per-engine ADMIN
  refused; break-glass refused. 100% matrix (R-TEST-01 guards/RBAC floor).
- **Audit-integrity**: fail-closed (DB down ⇒ zero engine registrations persisted, zero
  probes), before/after payload correct, secret refs redacted.
- **Seed/pin**: empty table imports YAML (audited `registry-seed`); non-empty ignores YAML +
  WARNs; `source: config` 403s every write and restores restart semantics.

## 14. Non-goals (v2 scope)

- Editing the egress allowlist in-app (deploy config, by design — §2 security seat).
- Setting secret **values** through the UI (env-refs only, iron rule).
- Renaming an engine `id` (immutable forever).
- Cross-inspector registry federation / sync.
- Registering non-Flowable-REST targets (the path whitelist assumes the V6/V7 REST shape).
</content>
</invoke>
