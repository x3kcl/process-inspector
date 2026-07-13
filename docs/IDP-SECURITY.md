# 🔐 IdP & SECURITY — identity wiring, access lifecycle & the who-can-do-what store (design + panel, v2)

**Status:** ★★ **FEATURE COMPLETE** (design locked 2026-07-09, **hardened by a 5-seat
panel + independent adversarial pass (Gemini 2.5)** — the panel changed the design, not just the
prose, see §2 and the `⚠️ panel:` markers). S1–S6 core BUILT 2026-07-09/10; login-time `auth_time`
conformance validator + membership re-pull verification landed 2026-07-12 (issue #95, §5); the
IdP-unreachable break-glass door landed 2026-07-14 (issue #94, §7); the Playwright grant-flow +
axe gate landed 2026-07-12 (issue #85). Remaining: the optional belt-and-suspenders
background-group-re-pull layer (§5, gated on `offline_access` — never assumed to run). Authoritative
source-of-truth for the
**IdP-and-Security extension** — the
WHAT/WHY/HOW/WHEN below drive the deltas into `SPECIFICATION.md` (new §2.4 + §4c),
`ARCHITECTURE.md` (§5), `IMPLEMENTATION-PLAN.md` (v2 IdP-Security block, S1–S6), `OPERATIONS.md`
(§7) and `REQUIREMENTS-REGISTER.md` (R-GOV-06 concretized; R-SAFE-07/12 expanded; R-SAFE-14 +
R-OPS-16 added). Mirrors the doc-per-feature convention of `REGISTRY-CRUD.md` — and reuses its
DB-authoritative-once-seeded mechanics for the mapping store.

**Discharges / concretizes:** R-GOV-06 (ADR-003 IdP contract — pilot IdP, claim format, issuer
pinning, Entra groups-overage), R-SAFE-07 (access lifecycle: session caps, cookie flags, bounded
claim re-evaluation, `GET /api/access-review`), R-SAFE-12 (the group→scope mapping moves file→DB
CRUD, change-audited — **v2 explicitly deferred here**), R-SAFE-06/R-SAFE-11 (break-glass, built —
only the audit column exists today), R-OPS-10 (the prod-like OIDC profile becomes a real Keycloak
CI leg), R-OPS-07 (transport / header hardening). Adds **R-SAFE-14** (the mapping-CRUD
`ACCESS_ADMIN` grant, the effective-grant escalation rails, the fail-closed authorization gate) and
**R-OPS-16** (security-header / cookie / CORS posture).

---

## 1. The problem & the benefit

The auth machinery is **built but half-inert**. `SecurityConfig` already ships a dual-profile
chain (dev Basic+form / prod `oidc` `oauth2Login`), the full VIEWER→RESPONDER→OPERATOR→ADMIN
ladder with a `RoleHierarchy`, `RbacAuthorizer` behind every `@PreAuthorize`, scoped
`(role, engineId, tenantId)` grants, `InspectorAuthoritiesMapper` (IdP groups → ladder
authorities at login), and `ScopeMappingService` (a hot-reloaded mounted YAML file, ≤60 s TTL,
content-hash-logged, fail-**safe** to last-good). What is **missing** keeps the tool from being
deployable to a real support org:

1. **OIDC is coded but unwired.** No `spring.security.oauth2.client` registration and no
   `inspector.security` block anywhere — the `oidc` chain points at no issuer, the scope-mapping
   file is unconfigured, so **zero** group grants would resolve. ADR-003 (R-GOV-06) is a promise:
   no pilot IdP named, no claim format pinned, no issuer pinning, no groups-overage handling.
2. **Group membership is frozen at login.** The *mapping* re-resolves at check time (a mid-incident
   grant to a group applies live) — but the user's *group set* is captured once from the login
   token and never refreshed. A user removed from a group at the IdP keeps their authority until the
   session ends. R-SAFE-07 wants ≤15 min re-evaluation; today it is unbounded. **The honest core of
   the feature — and, per the panel, harder than it first looks (§5).**
3. **No session lifecycle or transport hardening.** No idle/absolute session caps, no cookie
   attribute policy, no HSTS/CSP/nosniff/frame-ancestors — `SecurityConfig` configures none of
   `sessionManagement`/`headers`. The BFF leans on servlet + reverse-proxy defaults.
4. **The who-can-do-what store is a single mounted file.** R-SAFE-12 always promised **v2 CRUD**.
   Onboarding an engineer, or granting a group scope mid-incident, is an SSH-and-edit on a mounted
   volume — no UI, no per-change audit trail, no legibility.
5. **Break-glass is a schema field, not a flow.** The `breakGlass` audit column exists; the
   sealed-account / `/break-glass` path / banner / alert (R-SAFE-06/11) do not.
6. **No access-review export.** "Who can do what across the fleet" (R-SAFE-07) is unanswerable
   without reading the raw file.
7. **The live authorization gate fails _open_ today.** `RbacAuthorizer.canExecute` returns
   `.orElse(true)` for any path that doesn't resolve to a known `ActionVerb` — a deliberate
   "let the controller 404" shortcut that is, precisely, a fail-**open** authorization default in
   the enforcement core the whole feature reuses (⚠️ panel, §2 / §3.10).

The benefit is **a real, auditable identity boundary**: SSO login against the pilot IdP, group
membership that can't go stale on the dangerous verbs, session and transport posture a security
reviewer signs off (R-GOV-02), a UI to grant/revoke scope with a per-change audit trail, an
emergency lever for an IdP outage, and a one-click "who can do what" export. It completes the
"BFF is now stateful" arc for **identity** exactly as Registry-CRUD completed it for **engines**.

The catch that keeps it gated to v2, and is most of this document: **the group→scope mapping is
the single most privileged store in the tool.** A row in it can grant ADMIN-global — or
`REGISTRY_ADMIN` (repoint the credential vault) — to any IdP group. Editing it is higher-privilege
than any tier-3 verb and higher than Registry-CRUD, because it decides *who may do anything at all,
including administer engines*. **The guardrails on that CRUD — a distinct `ACCESS_ADMIN` grant,
four-eyes on effective-grant widening, a fail-closed gate, a boot invariant, an env-bootstrap
lock-out floor, and a break-glass audit fallback — are the load-bearing cost.**

---

## 2. Panel discussion (5 seats + adversarial pass, independent deliberations → synthesis)

Method mirrors `DESIGN-REVIEW.md` / `REGISTRY-CRUD.md`: five seats deliberated independently against
the real M4/v2 security code (`SecurityConfig`, `RbacAuthorizer`, `ScopeMappingService`,
`InspectorAuthoritiesMapper`, `ActionVerb`), then a sixth independent adversarial pass (Gemini 2.5)
red-teamed the locked draft, and conflicts were resolved. Seats: **security architect**, **lead
developer**, **DevOps/SRE**, **support-team lead**, **UX expert**. The panel materially changed the
design; the findings that moved it are below, and every one is carried into §3–§14.

### Security architect (the load-bearing seat)
- **The live gate fails open.** `RbacAuthorizer.canExecute` = `…map(hasRoleOn).orElse(true)` — an
  unknown verb path is *authorized*. A new mapping-write "verb," a rename, a sub-path, or a verb
  wired before its `ActionVerb` enum entry executes with **no role check**. This is the exact
  fail-open the design forbids, in the enforcement core. **Flip to `.orElse(false)` + a
  pre-`@PreAuthorize` verb-existence check** (§3.10).
- **`max_age` recorded ≠ enforced.** Recording `auth_time`/`iat` on the audit row proves nothing;
  Spring's OIDC login does not reject a token whose `auth_time` predates the window, and a silent
  SSO re-auth can return the *same stale membership*. Demand a custom `OidcIdTokenValidator` that
  **rejects** a stale/absent `auth_time` on the dangerous path, and that the acting grants resolve
  from the **post-re-auth session principal**, not a token reached at check time (§5).
- **The apex is above the ladder → its own grant.** A mapping row can mint ADMIN-global or
  `REGISTRY_ADMIN`; the mapping administrator is *higher* than `REGISTRY_ADMIN`. → **`ACCESS_ADMIN`**,
  orthogonal, never a rung, never conferred by ADMIN/`REGISTRY_ADMIN`, never by break-glass.
- **Wildcard breadth escapes four-eyes.** Self-widen + any-fleet-grant four-eyes still leaves a
  *ladder* grant of `ADMIN`/`*`/`*` to a group the editor is **not** in as **single-actor** — one
  click hands ADMIN-global to a broad group. **Extend unconditional four-eyes to any grant of role
  ≥ OPERATOR with `engineId='*'` OR `tenantId='*'`** (§3.4 / §6).
- **Apex removal is a takeover, not a fail-safe narrowing.** A coerced `ACCESS_ADMIN` can
  single-actor *remove every other* `ACCESS_ADMIN` and become the sole apex authority — after which
  no independent approver exists and four-eyes is a rubber stamp. **Fleet-grant removal is four-eyes,
  and a boot/write invariant keeps ≥2 independent `ACCESS_ADMIN` groups** (§3.4 / §6).
- Fail closed on identity ambiguity: zero groups = zero scope, **never** a default. A non-array
  `groups` claim (Keycloak scalar/CSV — `groupsOf` returns `List.of()` today) is a **legible
  failure**, not a silent zero (§4).

### Lead developer (cost & the real seams)
- **Forced re-auth is not "a near-free redirect."** `oauth2Login` only redirects when *unauthenticated*;
  the dangerous verbs are SPA `fetch` calls that cannot follow a 302. Forcing `max_age` needs a
  custom `OAuth2AuthorizationRequestResolver` **and** a **401-challenge → SPA full-page re-auth →
  verb-replay** protocol. Freshness is "verb replayed after the new principal is persisted," not an
  in-request token reach (`RbacAuthorizer.grantsFor` reads the session principal). Rewrite §5 to say
  so and drop "near-free."
- **Tier naming is wrong.** `ActionVerb` has tiers **0, 1, 3** (max = ADMIN, tier 3); there is no
  tier-4 *verb* — tier 4 is the bulk *guard* tier. The dangerous set is "**tier-3 verbs + bulk
  (guard-tier 4) + every mapping write**."
- **The `MappingSource` seam is too narrow.** The two methods it fronts (`grantsForGroups`,
  `rolesForGroups`) are keyed by a caller-supplied group set; access-review, the widen check, and
  drift all need **whole-mapping enumeration**. Add `allLadderGrants()` / `allFleetGrants()`,
  implemented by **both** `FileMappingSource` and `DbMappingSource` (so access-review/drift work
  under `mapping-source: file`, not silently empty).
- **`changeSessionId` churns the dev SSE path.** Basic re-authenticates every XHR; fixation re-IDs
  JSESSIONID each time, orphaning the long-lived `EventSource`. Apply the fixation strategy to the
  `oidc`/form chains; for the dev Basic chain keep session-id stable (authenticate-once-then-session
  or no strategy on `BasicAuthenticationFilter`). "Applies to both chains" as first drafted is false.
- **Don't pin `V8`.** `docs/M4-CLOSEOUT.md` already earmarks `V8/V9/V10` (audit-payload column,
  legal-hold). Say "next free Flyway version, allocated at merge time" + a coordination gate.
- **Profile-driven impl** keeps the large rung-3 `NoDbTestSupport` suite on the file source with
  zero new mocks: `FileMappingSource` default, `DbMappingSource` `@Profile("db")`.

### DevOps / SRE
- **File-pin can recover into a lock-out.** The mounted YAML is a one-time seed; realistic ops grant
  the first `ACCESS_ADMIN` via the UI *after* seeding. When the DB is bricked and an operator pins
  to `file`, the stale seed may hold **no** `ACCESS_ADMIN` → CRUD off *and* nobody holds the apex.
  Fix with a **boot invariant** (resolved mapping must contain ≥1 `ACCESS_ADMIN` in *both* modes,
  else fail startup loudly) **and** a config **env-bootstrap grant** `INSPECTOR_ACCESS_ADMIN_GROUP`
  that always seeds a known-good apex regardless of DB/file state — the true, always-available
  recovery floor.
- **Readiness is unstated and a zero-grant boot is a silent total lockout.** READY can't distinguish
  "seeded fine" from "seeded to zero" (every login → zero scope). State readiness = "mapping loaded
  (DB rows or file seed)" like R-OPS-15, **and** add a distinct health indicator + alert
  ("mapping resolves zero effective grants / seed import failed"), routed like
  `audit_insert_failures_total`. Break-glass reachability is independent of mapping readiness
  (normative).
- **Per-verb re-auth is an MFA-storm on Entra.** A P1 fan-out of 20 retries under Conditional-Access
  MFA = up to 20 interactive prompts, exactly when latency matters. Adopt a **bounded freshness
  window** (re-auth only when `auth_time` exceeds N min, N ≤ the 15-min budget); within-window verbs
  run on a token ≤N-min stale — bound and documented, not "just-minted every verb."
- **The one live deploy runs the dev chain**, so the whole `oidc`/break-glass/session path is never
  exercised on a running box. Stand the prod-like `oidc` profile up (drill break-glass + file-pin
  recovery) before pilot, tied to the OPERATIONS §4 restore-drill cadence. App HSTS stays **off by
  default** so it never double-emits `Strict-Transport-Security` alongside Traefik's deliberate
  `stsSeconds=300`.
- **Name Keycloak, not "a lightweight stub."** `max_age`/`prompt`, refresh-token issuance, and the
  overage shape are exactly what a minimal stub omits; make the `oidc` profile a **merge-blocking**
  CI leg budgeted against the 20-min integration cap (or split to its own job).

### Support-team lead (the 3 am operator)
- **Re-auth must not fire *after* the operator typed the token + reason.** If the `max_age` bounce
  hits at submit, the transient modal state (business key, reason) is lost and the operator faces a
  confusing double-confirm on the most dangerous verb. **Re-auth is a pre-condition at
  destructive-verb click / modal-open**, never at submit; land back with a server-fresh modal, then
  type (§5 / §12).
- **The IdP-down door has no visible lever.** When Entra is down the `oidc` chain redirects into a
  dead Microsoft page — the operator never reaches an inspector page that mentions `/break-glass`.
  **Resolved #94:** an in-app interstitial turned out to be structurally impossible (the SPA can
  never load pre-auth, and a down IdP's failure is never observable by this app's own origin) — the
  door is instead a directly-documented URL, `GET /break-glass`, serving a plain JS-free HTML form
  (§7). (Secondary: a per-session sticky reason for tier-0
  repeats under break-glass avoids garbage reasons in a 50-job recovery; keep per-verb reason on
  tier ≥1.)
- **A bulk-wizard draft is not a persisted job.** A session-cap guillotine mid-wizard destroys the
  typed reason + scope. **Warn-before-guillotine** (countdown banner), and never hard-interstitial
  while a form/wizard is dirty until the draft is checkpointed (§5 / §12).

### UX expert
- **Un-approvable proposals must say so.** The self-widen message names the gate but not the next
  move, and if every other `ACCESS_ADMIN` is in the affected group the eligible-approver set is
  **empty** — an un-approvable proposal that rots for the TTL and reads as "denied." Compute the
  eligible set at gate time and, when empty, say "**No eligible approver — recover via the file-pin,
  see RUNBOOK**" (§6 / §12).
- **Fleet grants need an intrinsic glyph, not just a band.** In the flat, sortable access-review
  table the "distinct band" evaporates and `ACCESS_ADMIN` reads like a per-engine ADMIN (fleet chips
  carry no env color either). Give fleet grants an **in-chip shape + glyph + "FLEET" token** that
  survives sort/filter/SR linearization, and add a grant-type column to the access-review table.
  Gate release on axe + an SR pass (§12).
- Own admin route `/admin/access`, greyed-never-hidden; the four-eyes path reuses the R-SAFE-08
  proposal/typed-confirm UI, Enter never submits.

### Sixth seat — adversarial pass (Gemini 2.5, independent, 2026-07-09)
- **IdP group-name / issuer collision.** A `groups` value equal to the `ACCESS_ADMIN` group name,
  minted by a *different* federated issuer/tenant, could mint the apex grant. **Pin a single trusted
  issuer** (Entra: pin the tenant, never the `common` multi-tenant endpoint); trust `groups` only
  from that issuer; fleet-grant group names are issuer-scoped (§4).
- **Break-glass ✗ when Postgres _is_ the outage.** Fail-closed audit means a DB outage blocks
  break-glass — total lockout in the exact scenario break-glass exists for. **Break-glass audit
  falls back to a local tamper-evident append-only file sink** when Postgres is unreachable — the
  one deliberate, loudly-flagged exception to fail-closed, reconciled to the DB on recovery (§7).
- **Four-eyes collusion on the apex.** Two `ACCESS_ADMIN`s can approve each other into a new
  `ACCESS_ADMIN` group. A small (≤25-user) org can't sustain a fully segregated approver class, so
  the compensating control is **detective**: every `ACCESS_ADMIN`-grant create/modify/remove
  **always fires the security alert channel** in addition to four-eyes; the small-org limit is
  stated, not hidden (§9).
- **`max_age` silent re-auth may not re-check membership** and **ID-token-vs-userinfo** group sources
  can disagree → adopt a **single documented authoritative group source** and, where reachable, a
  userinfo/Graph **re-pull at the dangerous tier** so freshness is of *membership*, not just of
  *authentication* (§4 / §5).
- **CSRF `Authorization`-header exemption** is *safe* here — clarified, not changed: CORS is off, so
  an attacker cannot set an `Authorization` header cross-origin without a preflight the browser
  blocks; the OIDC session-cookie path carries no such header and stays fully CSRF-token-protected
  (§8).
- **CSP for bpmn-js/AG-Grid/CodeMirror** is not free → **report-only first**, tune, then enforce;
  never brick the diagram; `.bjs-powered-by` (R-GOV-05) untouched (§8).

**Cross-seat consensus:** the live gate fails **closed** (`orElse(false)` + verb-existence check) ·
the mapping is the apex store → its own `ACCESS_ADMIN`, above `REGISTRY_ADMIN` · four-eyes triggers
on *effective-grant widening*, on *any* wildcard-breadth `≥OPERATOR` grant, and on *fleet-grant
removal*, with a ≥2-independent-`ACCESS_ADMIN` invariant · an env-bootstrap apex grant is the
always-available lock-out floor · dangerous actions run on a re-authenticated principal via a
**challenge+replay** protocol with a **bounded freshness window** (never per-verb MFA storms), and
membership freshness needs a userinfo re-pull, not just `max_age` · a single pinned issuer + single
authoritative group source · break-glass is ADMIN-global but never fleet, reachable when the IdP is
down, and its audit degrades to a local sink when the DB is down · headers/cookies hardened at the
app, CSP report-only-first, HSTS opt-in, CORS off · every mapping write and every access-review read
is audited fail-closed.

---

## 3. Boundary decisions (locked before build)

1. **OIDC becomes real; the dev chain is untouched.** The `oidc` profile gains a
   `spring.security.oauth2.client` registration (issuer-uri **pinned to one tenant**, client-id,
   client-secret-**ref**, scopes `openid profile <groups> [offline_access]`, PKCE, exact
   redirect-uri). ADR-003 names the pilot IdP + claim format + **issuer pinning** (§4). Dev
   Basic+form+ladder stays exactly as-is.
2. **Group→scope mapping: DB once seeded; mounted YAML = one-time seed + pin.** Empty
   `group_scope_grant`/`group_fleet_grant` ⇒ import the mounted file as seed rows (audited
   `mapping-seed`). Non-empty ⇒ DB wins, file ignored with a per-group drift WARN.
   `inspector.security.mapping-source: file|db` pins to file-only (CRUD off, today's exact
   semantics). Flyway owns the schema (**next free version, allocated at merge time —
   coordinate with M4-closeout's `V8/V9/V10`**); `ddl-auto=validate` holds.
3. **`ACCESS_ADMIN` is the apex orthogonal fleet grant** — administers the mapping incl. the
   assignment of `REGISTRY_ADMIN` and of `ACCESS_ADMIN` itself. Own OIDC group; per-engine ADMIN and
   `REGISTRY_ADMIN` do **not** confer it; break-glass does **not** grant it. Checked by
   `rbac.canAdministerAccess(authentication)`.
4. **Four-eyes triggers on effective-grant widening — computed on the resolved grant set, not
   text.** A second approver (∉ the affected group, ≠ proposer) is **mandatory** for: (a) adding or
   raising a grant to a group the editor is in; (b) **any** grant of role ≥ OPERATOR with
   `engineId='*'` OR `tenantId='*'` (breadth, regardless of editor membership); (c) creating **any**
   fleet grant; (d) **removing a fleet grant** (apex removal is a takeover, not a fail-safe
   narrowing). Narrowing/removing a *ladder* grant is single-actor. A **boot + write invariant** keeps
   **≥2 independent `ACCESS_ADMIN` groups** at all times.
5. **Identity freshness on the dangerous set** (tier-3 verbs + bulk guard-tier 4 + every mapping
   write). Mandatory floor: session caps (idle 12 h / absolute 24 h) + `HttpOnly; Secure;
   SameSite=Lax` + fixation protection. The dangerous set additionally runs under a **challenge +
   SPA re-auth + verb-replay** protocol with a **bounded freshness window** (re-auth only when
   `auth_time` exceeds N min, N ≤ 15) enforced check-time by `DangerousActionReauthGate` and
   login-time by `ReauthConformantOidcUserService` (issue #95); where a groups
   endpoint is reachable, membership is **re-pulled** at that moment (freshness of membership, not
   just of authentication). A background ≤15 min re-pull is **optional**, gated on `offline_access`,
   never assumed. On the dev chain this degrades to today's typed-token confirm (dev is ADMIN-global).
6. **Zero groups = zero scope; identity ambiguity fails closed.** No default role, ever. A non-array
   `groups` claim and an Entra groups-overage are **detected** and either resolved (permissioned
   Graph add-on) or **rejected with a legible error** — never a silent zero. A single **pinned
   issuer** and a single **authoritative group source** are documented (§4).
7. **Mapping CRUD is audited fail-closed** (`audit_entry`, synthetic `instance_id` = group name,
   `action` = `mapping-*`, payload = before/after **resolved** grants). The **access-review read is
   also audited** (a security-relevant disclosure — a recon oracle).
8. **Break-glass is built (R-SAFE-06/11)** — a distinct sealed-account chain + `/break-glass` path,
   4 h session, ADMIN-global (never fleet), loud + alert-on-login + reason-on-every-verb, reachable
   when the IdP is down. Its audit degrades to a **local tamper-evident file sink** when Postgres is
   unreachable (the one deliberate fail-closed exception — §7). Prod-only.
9. **Transport/header hardening at the app, config-bounded.** `nosniff`, `frame-ancestors 'none'`,
   `Referrer-Policy`, `Permissions-Policy` on by default; **CSP report-only-first**, **HSTS opt-in**
   (never double-emit vs the proxy). CORS stays **off** (single-origin by design).
10. **The live authorization gate fails closed.** `RbacAuthorizer.canExecute` flips from
    `.orElse(true)` to `.orElse(false)`; the 404-vs-403 UX is resolved by a verb-existence check in
    the controller/registry **before** `@PreAuthorize` — the authorization decision never defaults to
    allow. An **env-bootstrap `ACCESS_ADMIN` grant** (`INSPECTOR_ACCESS_ADMIN_GROUP`) is the
    always-available lock-out floor; the boot invariant (§3.4) fails startup loudly if no apex grant
    resolves.

---

## 4. OIDC / IdP wiring — ADR-003 concretized (R-GOV-06)

The `oidc` chain exists; this makes it a contract.

- **Pilot IdP:** Microsoft **Entra ID** (Azure AD) named pilot; **Keycloak** the self-hosted /
  air-gapped **and CI** alternative (§13/§14 — the freshness/overage/refresh semantics need a real
  OIDC engine, not a stub). Both are standard OIDC issuers; the BFF binds to the `issuer-uri` deploy
  config names — no per-IdP code.
- **Issuer pinning (⚠️ sixth-seat).** `issuer-uri` is pinned to **one tenant** (never Entra's
  `common`/`organizations` multi-tenant endpoint). `groups` is trusted **only** when the validated
  `iss`/`aud` match the pinned registration; a token from any other issuer resolves **zero** groups.
  Fleet-grant group names are therefore issuer-scoped — a same-named group from a foreign tenant
  cannot mint `ACCESS_ADMIN`/`REGISTRY_ADMIN`.
- **Flow:** authorization-code + **PKCE**, `nonce` + `state` (Spring defaults), **exact
  redirect-uri**, tokens exchanged and held **server-side in the session only** (the SPA carries no
  token — do not regress). Scopes `openid profile` + the groups scope + optional `offline_access`
  (only if the background re-pull, §5, is enabled).
- **Single authoritative group source (⚠️ sixth-seat).** One documented source per deploy — the
  configured `groups` claim by default; the userinfo/Graph endpoint where overage or dangerous-tier
  re-pull requires it — consistently applied, with reconcile-or-reject on a material discrepancy at
  the dangerous tier. Identity from `sub` (stable) + `preferred_username`/`email` for display.
- **Claim shape is validated, not assumed.** `groups` present but **not a JSON array** (Keycloak
  scalar / CSV group claims — `InspectorAuthoritiesMapper.groupsOf` returns `List.of()` today) is a
  **legible login failure**, not a silent zero. OIDC delivers **identity + coarse group IDs only**;
  the (role, engine, tenant) meaning is BFF-owned (§6), never trusted from a token.
- **Entra groups-overage (the concrete claim trap):** >~200 groups ⇒ Entra omits `groups` and emits
  `_claim_names` + `_claim_sources` pointing at Graph `getMemberObjects`. The mapper must
  (1) **detect** the marker; (2) either **resolve** via a permissioned Graph call (app registration
  holds `GroupMember.Read.All`/`User.Read` + a Graph-audience token — a documented, deploy-config
  add-on) or (3) **reject the login legibly** ("your identity provider returned more groups than fit
  in the token; group resolution isn't enabled here — contact an administrator") and resolve **zero**
  groups. Floor = detect + legible-fail. A silent zero-group login for an overage user is a **Sev1**
  (a quiet lie about why access was denied — R-TEST-03).
- **CI/test:** the R-OPS-10 prod-like profile is a **real Keycloak** compose leg (realm imported)
  so the `oidc` chain, groups claim, overage (stubbed shape), `max_age` re-auth, and refresh-token
  re-pull are all exercised without a cloud tenant (§13-S1, merge-blocking).

---

## 5. Access lifecycle & session hardening (R-SAFE-07)

Three layers, honest about what each guarantees. **The dangerous set** = tier-3 `ActionVerb`s +
bulk (guard-tier 4) + every mapping write (there is no tier-4 *verb*).

- **Session caps + cookie flags (mandatory, cheap).** `sessionManagement`: idle **12 h**, absolute
  **24 h**; **fixation protection** — but *scoped correctly*: `changeSessionId` on the `oidc`/form
  chains only. On the dev Basic chain (which re-authenticates every XHR) the session id stays
  **stable** so the long-lived SSE `EventSource` isn't orphaned (⚠️ lead-dev — the first-draft
  "applies to both chains" was wrong). Cookie: `HttpOnly; Secure; SameSite=Lax` on `JSESSIONID`.
  A bulk **job** survives session expiry (persisted server object, R-SEM-10); only *cancel* needs a
  live session. **A bulk-wizard _draft_ is not a job** — see the guillotine rule below.
- **Forced re-authentication on the dangerous set (the freshness lever, honestly built).** Spring's
  `oauth2Login` will not force `max_age` for an already-authenticated `fetch` call, and an SPA XHR
  cannot follow a 302. So this is a **protocol, not a redirect**: a custom
  `OAuth2AuthorizationRequestResolver` injects `max_age`/`prompt`; the BFF answers the dangerous
  request with a **401 + a re-auth-required marker** *at verb intent* (destructive-verb click /
  modal-open — **never after the operator has typed the confirm token + reason**, ⚠️ support-lead);
  the SPA runs a full-page re-auth interstitial, lands back on the same instance with a server-fresh
  modal, and replays the verb. Enforcement is two layers deep (issue #95, landed): **check-time**
  (`DangerousActionReauthGate`) rejects a dangerous verb whose session `auth_time` is older than the
  freshness window (fail-closed if absent); **login-time**
  (`ReauthConformantOidcUserService`, belt-and-suspenders) fails the re-auth login itself, at the
  token-response boundary, if a nonconforming IdP silently ignored `max_age` and echoed a stale/absent
  `auth_time` — so a broken IdP surfaces immediately, not as a confusing 401 minutes later on an
  unrelated verb. (Spring's `oauth2Login` has no built-in `auth_time`/`max_age` validator — confirmed
  by decompiling the default `OidcIdTokenValidator` chain — hence the two purpose-built gates rather
  than a validator hook.) The acting grants resolve from the **post-re-auth session principal**
  (`RbacAuthorizer.grantsFor`) — the guarantee is "verb replayed after the new principal is
  persisted," not an in-request token reach; proven fresh-by-construction (no identity-keyed cache
  anywhere in `RbacAuthorizer`) by `RbacAuthorizerOidcFreshnessTest`.
  **Bounded freshness window** (⚠️ DevOps): re-auth fires only when `auth_time` exceeds **N min**
  (N ≤ 15, config); within-window verbs run on a token ≤N-min stale — no per-verb MFA storm during a
  P1 fan-out. `max_age` bounds *authentication* recency; where a groups endpoint is reachable the BFF
  **re-pulls membership** at re-auth so freshness is of *membership*. **The approver's four-eyes
  click is itself a mapping-write-class act** — re-auth-gated and `auth_time`-validated too, so the
  "∉ affected group" test is computed on a fresh approver membership.
- **Background group re-pull (optional, gated, stated).** *If* `offline_access` + a refresh token are
  issued, a ≤15 min background job re-pulls the group set and updates the session; a group removed at
  the IdP is reflected within the window. On an IdP that issues no refresh token this layer **does
  nothing** — staleness is then bounded only by the session caps + dangerous-set re-auth, and the
  UI/docs say so. Never implied to always run.
- **Session-cap guillotine (⚠️ UX/support).** Absolute-cap expiry is unpredictable; a full-screen
  takeover mid-typing violates R-UXQ-06 and destroys a dirty wizard/modal draft. Rule:
  **warn-before-guillotine** (an idle-tolerant countdown banner, "session expires in N min — re-auth
  now"), and **never** hard-interstitial while a form/wizard is dirty until the draft (wizard step +
  reason) is checkpointed; R-SEM-14's preservation extends to the bulk-wizard draft, not just
  route+drawer.

**`GET /api/access-review`** (R-SAFE-07) — the effective-grant export: the full mapping expansion
(group → [(role, engine, tenant)] + fleet grants), each row tagged `source: file-seed|ui`, plus the
caller's own effective session grants. `ACCESS_ADMIN`-gated, **audited read**, CSV + Markdown + JSON.
The release-gate "who can do what" artifact (R-GOV-02).

---

## 6. Group→scope mapping CRUD (R-SAFE-12, v2) — the apex store

Mechanics are Registry-CRUD's; the *governance* is stricter because this store grants authority
itself.

```
boot:
  resolve the effective mapping from the active source (file | db-once-seeded) AND overlay the
    env-bootstrap apex grant (INSPECTOR_ACCESS_ADMIN_GROUP) if set  # always-available lock-out floor
  INVARIANT: the resolved mapping MUST contain ≥1 ACCESS_ADMIN group AND ≥2 independent ACCESS_ADMIN
    groups for CRUD to be enabled; else fail startup loudly with the remediation (never boot into a
    silent no-apex lock-out).
  if mapping-source == file:  mapping resolves from the mounted YAML (today's path); CRUD 403s
  else (db):
      if store empty:  import the mounted file as seed rows  (audit: mapping-seed)
      mapping resolves from the store (short in-memory cache, ≤60 s freshness, same as the file TTL)

write (grant-add | grant-edit | grant-remove | group-add | group-remove), ACCESS_ADMIN, audited fail-closed:
  audit.beginPending (fail-closed)
     → validate (role/slug/tenant well-formed; duplicate-tuple; ≥2-ACCESS_ADMIN invariant preserved)
     → widen check on the effective RESOLVED grant set (before vs after):
          if (self-widen) OR (role ≥ OPERATOR with engineId='*' OR tenantId='*')
             OR (create ANY fleet grant) OR (remove a fleet grant):
                require a second approver (∉ affected group, ≠ proposer, freshly re-authenticated)
                — compute the eligible-approver set NOW; if empty, refuse with the file-pin next-move
          ACCESS_ADMIN-grant changes ADDITIONALLY fire the security alert channel (detective control)
     → persist rows (@Transactional)  ┐
     → audit.close(ok)                ┘ ONE transaction (rows + audit outcome commit together)
     → afterCommit { MappingSource.refresh() }  # in-memory cache reload; check-time resolution picks it up
```

- **The `MappingSource` seam** fronts `ScopeMappingService`'s two group-keyed methods **and** adds
  whole-mapping enumeration `allLadderGrants()` / `allFleetGrants()` (⚠️ lead-dev) — implemented by
  **both** `FileMappingSource` (default, `!db`) and `DbMappingSource` (`@Profile("db")`), so
  access-review + drift work under `mapping-source: file` and the huge rung-3 suite keeps the file
  source with zero new mocks. The enforcement path (`RbacAuthorizer`/`InspectorAuthoritiesMapper`)
  updates for free — the same "every consumer re-reads live" property Registry-CRUD relied on.
- **The widen check** is on the **effective resolved grant set** (before vs after), so a
  reorder/rename cannot launder an escalation. Triggers per §3.4.
- **Check-time resolution is preserved** — a committed grant applies to live sessions within the
  cache TTL, exactly as the file does today (the editor's own dangerous-set actions force re-auth
  regardless — §5).
- **`id` semantics:** grants are value tuples (`UNIQUE(group, role, engine, tenant)` / fleet unique
  per group), so edit = delete+insert; there is no mutable natural key.
- **Fail-closed reload:** an `afterCommit` refresh failure never rolls back a committed grant; the
  next TTL read reconciles and logs — identical to Registry-CRUD.

**Drift is never silent:** under `mapping-source: db` with the file also present, boot logs a
**per-group drift report** and `GET /api/admin/access/drift` + an admin-UI badge surface it — and
the drift report **hard-alerts** if the pinned file lacks a resolvable `ACCESS_ADMIN` (a pin target
that can't self-administer is a lock-out, ⚠️ DevOps).

---

## 7. Break-glass (R-SAFE-06 / R-SAFE-11) — built

Only the `breakGlass` audit column exists today; this builds the flow. Normative semantics are
already in SPEC §6.

- **A distinct chain + path.** `/break-glass` authenticates a **single sealed local ADMIN account**
  (env-ref `INSPECTOR_BREAK_GLASS_PASSWORD`, rotate-after-use documented). Prod-only; separate from
  the `oidc` chain so it works **when the IdP is down** — its reason to exist.
- **The IdP-down door is reachable (⚠️ support-lead, resolved #94).** `GET /break-glass` serves a
  plain, JS-free HTML sign-in form (`BreakGlassLoginPageController`) at a directly-documented URL
  (RUNBOOK §4) — not an auto-surfaced SPA interstitial: every non-`/api` path is `authenticated()`,
  so the SPA can never load pre-auth, and there is no "IdP unreachable" event it could ever observe
  or react to (a down IdP fails as the browser's own native network error on the IdP's *foreign*
  origin, never a response this app's own origin sees). CSRF rides a server-rendered hidden field,
  not the SPA's cookie/header echo, so the door works even with JavaScript disabled. This is the
  *getting-in* half of R-SAFE-11's "dead simple and loud."
- **Scope:** ADMIN-**global** (bypasses authN + the group→scope mapping only). Does **not** bypass
  the guard ladder, read-only engine mode, the path whitelist, or fail-closed audit. Grants
  **neither** `ACCESS_ADMIN` **nor** `REGISTRY_ADMIN` — an IdP outage is not a licence to rewrite
  authority or repoint the vault. A catastrophic mapping edit is recovered via the **env-bootstrap
  apex grant** (`INSPECTOR_ACCESS_ADMIN_GROUP` + redeploy) or the **file-pin** — documented in the
  RUNBOOK as the deliberate lock-out escape.
- **Audit degrades, never blocks (⚠️ sixth-seat).** Fail-closed audit needs Postgres — but a DB
  outage may be *concurrent* with the IdP outage, and blocking break-glass then is a total lockout in
  the one scenario it exists for. So break-glass audit falls back to a **local tamper-evident
  append-only file sink** (write-success gates the action) when Postgres is unreachable — the single
  deliberate exception to fail-closed — loudly flagged, and reconciled into `audit_entry` on
  recovery. Normal-path break-glass still writes to Postgres fail-closed.
- **Loudness:** `breakGlass:true` on every audit row (first in the shift report), a **permanent red
  page banner**, the **alert channel fires on login** (R-OPS-03), a mandatory reason **≥10 on every
  verb incl. tier 0** (with a per-session **sticky reason** for tier-0 repeats so a 50-job recovery
  doesn't breed garbage reasons; per-verb reason stays on tier ≥1), any `require-second-approval`
  **waived-but-flagged**.
- **Session:** 4 h absolute cap (< the normal 24 h). No refresh, no re-auth (there is no IdP).

---

## 8. Transport / header / cookie posture (R-OPS-07, new R-OPS-16)

`SecurityConfig` configures **none** of this today. Added, defense-in-depth over the reverse proxy,
**config-bounded so nothing bricks**:

| Control | Default | Note |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | always on |
| Frame protection | `frame-ancestors 'none'` (CSP) + `X-Frame-Options: DENY` | never framed |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | always on |
| `Permissions-Policy` | deny geolocation/camera/mic/usb etc. | always on |
| **CSP** | **report-only first**, then enforce | tuned against the real bpmn-js + AG-Grid + CodeMirror + inline-SVG bundle; `worker-src`/`wasm` verified before enforce; **must not touch `.bjs-powered-by`** (R-GOV-05); a config flag keeps it report-only per deploy |
| **HSTS** | **opt-in, off by default** | the demo/proxy owns HSTS (deliberately weak `stsSeconds=300` to avoid cert-churn lock-out); the app must never double-emit `Strict-Transport-Security` |
| Cookie flags | `HttpOnly; Secure; SameSite=Lax` | §5 |
| CSRF | unchanged, **clarified** | the cookie repo protects the **OIDC session-cookie path** fully (no `Authorization` header → not exempt). The exemption covers only the dev/Basic header-auth path, which **cannot be forged cross-origin**: adding an `Authorization` header makes a request non-simple, forcing a CORS preflight that — with **CORS off** — the browser blocks. Safe by construction (⚠️ sixth-seat, clarification not change) |
| **CORS** | **off** | single-origin by design (SPA served same-origin) — stated, not an oversight; enabling it is a new attack surface with no consumer |

---

## 9. RBAC & governance

- **New grant `ACCESS_ADMIN`** — the apex fleet authority, administers the group→scope mapping (incl.
  the assignment of `REGISTRY_ADMIN` and `ACCESS_ADMIN`). `ROLE_ACCESS_ADMIN` in the dev chain; own
  OIDC group in prod. Checked by `rbac.canAdministerAccess(authentication)`, at the door
  (`@PreAuthorize`) **and** re-checked in the service.
- **Grant lattice** (both fleet grants orthogonal to the ladder and to each other):
  ```
  VIEWER < RESPONDER < OPERATOR < ADMIN          (the per-engine/tenant ladder)
  REGISTRY_ADMIN   — repoints the credential vault (engines)             [orthogonal]
  ACCESS_ADMIN     — defines who holds every grant, incl. the two above  [orthogonal, apex]
  ```
- **Governance ladder:** read mapping / access-review = `ACCESS_ADMIN` (**audited read** — recon
  oracle); narrow/remove a *ladder* grant = `ACCESS_ADMIN` + reason ≥10; self-widen, any wildcard
  `≥OPERATOR` grant, any fleet-grant create, any fleet-grant remove = `ACCESS_ADMIN` + reason +
  **four-eyes** (R-SAFE-08) **+ a security-alert-channel fire** (the detective backstop against
  approver collusion — a ≤25-user org can't fully segregate an approver class, stated honestly).
- **Audit** every write via the fail-closed `AuditService`: `action ∈ {mapping-seed, grant-add,
  grant-edit, grant-remove, group-add, group-remove}`, `instance_id` = group name, `payload` =
  before/after **resolved** grants, `outcome` PENDING→ok|failed.
- **The `≥2 independent ACCESS_ADMIN` invariant** is enforced at boot and on every write; the
  env-bootstrap grant (`INSPECTOR_ACCESS_ADMIN_GROUP`) is the always-available floor that also seeds
  the first apex on a fresh deploy.
- **Break-glass** grants ADMIN-global but **not** `ACCESS_ADMIN`/`REGISTRY_ADMIN`; mapping CRUD and
  Registry CRUD are both unavailable under break-glass (greyed-with-reason).

---

## 10. API surface (ARCH §5 additions)

| Endpoint | Purpose |
|---|---|
| `GET  /api/admin/access` | Full mapping: groups → grant rows (ladder + fleet), `source` tag. `ACCESS_ADMIN`. Audited read. |
| `POST /api/admin/access/grants` | Add a grant. Validated; widen/breadth/fleet ⇒ four-eyes (+ alert); reason ≥10; audited. |
| `PUT  /api/admin/access/grants/{id}` | Edit a grant tuple (= remove+add). Re-validated; widen ⇒ four-eyes; audited before/after. |
| `DELETE /api/admin/access/grants/{id}` | Remove a grant. Ladder-narrow = single-actor; **fleet remove = four-eyes** (+ alert); ≥2-apex invariant enforced; reason ≥10; audited. |
| `GET  /api/admin/access/drift` | DB-vs-file drift report; **hard-alerts if the pinned file lacks a resolvable `ACCESS_ADMIN`**. `ACCESS_ADMIN`. |
| `GET  /api/access-review` | Effective grant-tuple export (mapping expansion + caller's session grants), with a **grant-type column (ladder\|fleet)**. `ACCESS_ADMIN`. CSV/Markdown/JSON. Audited read. |
| `GET  /break-glass` | The door itself (#94): a plain, JS-free HTML sign-in form at a directly-documented URL (RUNBOOK §4) — not an SPA route, since the SPA can never load pre-auth. `permitAll`; a 404 when unconfigured (never reveals whether break-glass exists). |
| `POST /break-glass` (form) | Sealed-account login on the break-glass chain (prod). Fires the alert, opens the 4 h ADMIN-global session, banners every page. |
| `GET  /api/me` (existing) | Gains `accessAdmin: boolean` + `breakGlass: boolean` hints (additive on `MeDto`) so the SPA greys the admin nav + shows the banner. |

OIDC `issuer-uri`/`client-id`/secret-ref, `INSPECTOR_ACCESS_ADMIN_GROUP`, and
`INSPECTOR_BREAK_GLASS_PASSWORD` are **deploy config**, never API. No generic proxy route; the path
whitelist is unchanged.

---

## 11. Data model (`V<next>__group_scope_mapping.sql`)

Flyway version is **allocated at merge time** (`V8/V9/V10` are contended by M4-closeout — ⚠️
lead-dev). Follows the V6/V7 shape (identity PK, `@Table` entity, `JpaRepository`,
`@Transactional` store, `ddl-auto=validate`). Two tables — a fleet grant must never read as a
`(role, engine, tenant)` row.

```sql
-- ladder grants: (group → role on an engine/tenant scope)
CREATE TABLE group_scope_grant (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_name  text NOT NULL,
    role        text NOT NULL CHECK (role IN ('VIEWER','RESPONDER','OPERATOR','ADMIN')),
    engine_id   text NOT NULL DEFAULT '*'
                  CHECK (engine_id = '*' OR engine_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),  -- R-SEM-08
    tenant_id   text NOT NULL DEFAULT '*',
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    source      text NOT NULL DEFAULT 'ui' CHECK (source IN ('ui','file-seed')),
    UNIQUE (group_name, role, engine_id, tenant_id)
);

-- orthogonal fleet grants: (group → REGISTRY_ADMIN | ACCESS_ADMIN)
CREATE TABLE group_fleet_grant (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_name  text NOT NULL,
    grant_kind  text NOT NULL CHECK (grant_kind IN ('REGISTRY_ADMIN','ACCESS_ADMIN')),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    source      text NOT NULL DEFAULT 'ui' CHECK (source IN ('ui','file-seed')),
    UNIQUE (group_name, grant_kind)
);
CREATE INDEX idx_group_scope_grant_group ON group_scope_grant (group_name);
CREATE INDEX idx_group_fleet_grant_group ON group_fleet_grant (group_name);
```

No secret, no token, no OIDC config lives in these tables — identity comes from the IdP, secrets stay
env-refs. Break-glass and the env-bootstrap apex grant have **no** table (sealed env-refs + the
existing audit column + the local file sink).

---

## 12. Frontend (SPEC §4c)

- Route `/admin/access`, `ACCESS_ADMIN`-gated, **greyed-never-hidden** with the reason; sibling to
  `/admin/engines`.
- **Mapping view:** group → grant rows; ladder grants as env-banded shape+label chips; fleet grants
  (`REGISTRY_ADMIN`/`ACCESS_ADMIN`) with an **intrinsic in-chip shape + glyph + "FLEET" token** (not
  merely a band — it must survive table sort/filter + SR linearization, ⚠️ UX); `ACCESS_ADMIN` the
  loudest chip after the break-glass banner. Add/edit form with duplicate + well-formed validation;
  the widen path shows the **concrete next move and the computed eligible-approver set** — "Propose →
  an `ACCESS_ADMIN` not in `<group>`"; when that set is empty, "**No eligible approver — recover via
  the file-pin, see RUNBOOK**", never a bare four-eyes prompt (⚠️ UX/support). The proposal is typed
  in the R-SAFE-08 inbox as a **governance/self-widen** proposal, distinct from action proposals.
  Enter never submits.
- **Dangerous-verb re-auth** fires at click/modal-open (never at submit); the interstitial returns to
  the same instance with a server-fresh modal (⚠️ support-lead). Session-cap re-auth is
  **warn-before-guillotine**, never a surprise takeover over a dirty draft (⚠️ UX/support).
- **The break-glass door** — `GET /break-glass`, a directly-documented URL rather than an
  in-app interstitial (structurally impossible pre-auth — see §7) (#94).
- **Access-review screen:** filterable "who can do what" table with a **grant-type column
  (ladder|fleet)** + CSV/Markdown export.
- Types flow through `npm run gen:api` from the running BFF (never hand-written).

---

## 13. Implementation slices → see `IMPLEMENTATION-PLAN.md` (v2 IdP-Security block)

Ordered so the identity foundation, the fail-closed gate, and the escalation rails land and are
tested behind nothing before any UI reaches them:

- **S1 — OIDC wiring + ADR-003 + issuer pinning + overage.** Real `oauth2-client` registration; the
  **Keycloak** prod-like/CI leg (merge-blocking); issuer/tenant pinning; the non-array-claim +
  Entra-overage detect-and-legibly-fail (Graph resolution opt-in). Dev chain untouched. Rung-3
  Keycloak-login IT.
- **S2 — session + header hardening + fail-closed gate.** `sessionManagement` (caps; fixation scoped
  to `oidc`/form, **not** the dev Basic-SSE path), cookie flags, the header set, CSP **report-only**,
  HSTS opt-in; and **`canExecute` → `.orElse(false)`** + the pre-auth verb-existence check. Rung-3
  test: JSESSIONID stable across consecutive Basic XHRs with an SSE stream open; unknown verb → 403.
- **S3 — mapping store (file→DB).** Flyway `V<next>` + entities/repos + `@Transactional` store + the
  `MappingSource` seam (incl. `allLadderGrants`/`allFleetGrants`, both impls) + file-seed import +
  `mapping-source: file|db` pin + the **env-bootstrap apex grant** + the **≥2-`ACCESS_ADMIN` boot
  invariant**. Profile-driven impl (`DbMappingSource` `@Profile("db")`) so the rung-3 suite is
  undisturbed. `NoDbTestSupport` repo mocks scoped to DB-store tests only.
- **S4 — `ACCESS_ADMIN` + mapping CRUD API + governance.** `rbac.canAdministerAccess`,
  `AdminAccessController`, the **effective-grant widen/breadth/fleet-removal check** + four-eyes
  (proposer & approver both re-authenticated) + the security-alert fire, fail-closed audit
  (`mapping-*`), the drift endpoint (with the no-apex hard-alert). Full RBAC + escalation-refusal +
  audit-integrity matrix.
- **S5 — dangerous-set re-auth protocol + break-glass.** The custom
  `OAuth2AuthorizationRequestResolver` + `OidcIdTokenValidator` + the 401-challenge/replay + bounded
  window + membership re-pull; the sealed break-glass chain + `/break-glass` + IdP-unreachable door +
  banner + alert + local-file-sink audit fallback + sticky tier-0 reason.
- **S6 — admin UI + access-review + `/api/me` hints.** `/admin/access`, the fleet-chip glyph, the
  eligible-approver legibility, the access-review screen + export, the warn-before-guillotine + re-auth
  interstitial, gen:api. Playwright smoke + axe + SR pass.

Each slice: rung-1 unit → rung-3 Spring wiring/RBAC → rung-4 Keycloak/Testcontainers IT → Playwright.

## 14. Test strategy (highlights)

- **Fail-closed gate (S2, CI-gating):** an unknown/unregistered verb path returns 403 (or 404 from
  the pre-auth existence check), **never 200** — a quiet allow = Sev1 (R-TEST-03).
- **Escalation matrix (S4, CI-gating):** ladder-narrow single-actor; self-widen, any wildcard
  `≥OPERATOR` grant, any fleet create, **and fleet removal** demand four-eyes; the check is on the
  resolved grant set (a reorder/rename that widens is caught); the ≥2-`ACCESS_ADMIN` invariant blocks
  the last-apex removal; an approver with a **stale** membership is rejected. Any quiet self-grant = Sev1.
- **Identity-freshness IT (Keycloak):** a group removed at the IdP does not authorize a tier-3 verb
  after the forced re-auth resolves fresh membership; within-window verbs are **not** re-prompted
  (no MFA storm); session caps expire on a `Clock` bean (Awaitility, never `Thread.sleep`).
- **Overage / claim-shape IT:** overage with resolution disabled, and a non-array `groups` claim,
  each fail **legibly** and resolve **zero** groups — never a default, never a silent zero-as-success.
- **Issuer-pinning IT:** a token from a non-pinned issuer/tenant resolves zero groups even if it
  carries the `ACCESS_ADMIN` group name.
- **Break-glass IT:** works with the `oidc` chain down; reachable from the IdP-unreachable door;
  every action `breakGlass:true` + reason-gated; **audit degrades to the file sink when Postgres is
  down and the action still proceeds** (then reconciles); cannot reach mapping/registry CRUD.
- **RBAC matrix:** `ACCESS_ADMIN` required at door + service; ADMIN/`REGISTRY_ADMIN`/break-glass
  refused for both fleet CRUD surfaces. 100 % (R-TEST-01).
- **SSE-stability IT:** JSESSIONID stable across consecutive dev-Basic XHRs while an `EventSource` is
  open (fixation does not orphan the stream).
- **Audit-integrity:** mapping writes fail-closed (DB down ⇒ zero grant changes persisted), the
  access-review **read** audited, before/after resolved payload correct.
- **CSP:** report-only build renders bpmn-js/AG-Grid/CodeMirror with zero violations before any
  enforce flag; `.bjs-powered-by` untouched (R-GOV-05 still green).
- **Ops drill (pre-pilot):** the prod-like `oidc` box exercises break-glass login + alert + the
  file-pin / env-bootstrap recovery on a running system, tied to the OPERATIONS §4 restore-drill
  cadence — never first-run at 3 am.

## 15. Non-goals (v2 scope)

- SCIM / automated deprovisioning (the ≤15 min re-eval + session caps are the deprovisioning bound).
- Per-user grants (grants are group-keyed; a "group of one" is the escape hatch).
- Editing OIDC issuer/client config in-app (deploy config, iron rule).
- A separately-provisioned "security officer" approver class for the four-eyes (rejected: a ≤25-user
  org can't sustain it — the detective security-alert on every apex-grant change is the compensating
  control, §9).
- Storing secret **values** or tokens anywhere but the session/env (iron rule).
- mTLS / client-certificate auth to the BFF; hardware-token step-up beyond OIDC `max_age` re-auth.
- Federating the mapping across inspector instances (single-instance store, like the registry).
