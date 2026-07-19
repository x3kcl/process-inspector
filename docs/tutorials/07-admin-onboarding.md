# Tutorial 07 — Admin onboarding: register an engine, understand the access rails

**Sign in as:** `admin` first (step 1), then `registry-admin`, then `access-admin`
(password `dev`) at <https://pi.naumann.cloud>.

**You will learn:** why engine-ADMIN, REGISTRY_ADMIN and ACCESS_ADMIN are three different
things, how a new engine earns trust (draft → probe → enable), what the SSRF rails refuse
and how, and how the group→scope grant flow is governed.

## Steps

1. **See the orthogonality as `admin`.** In the header, **Engines** and **Access** are
   greyed — hover: "Requires REGISTRY_ADMIN" / "Requires ACCESS_ADMIN". A full engine ADMIN
   deliberately cannot touch the registry (the credential vault) or the access mapping (the
   most privileged store in the tool). Greyed-never-hidden: you can see the surfaces exist
   and exactly which grant you lack.
2. **Sign in as `registry-admin`** and open **Engines** (`/admin/engines`) — "Engine
   registry". Read the table: per engine its **Lifecycle** (`✓ Active` here; the full
   ladder is `○ Draft → ● Probed / ▲ Probe failed → ✓ Active → ⏸ Disabled → 🗑 Removed`),
   **Mode** (read-write vs read-only — the rollout ramp: prod engines onboard read-only
   until the owning team signs off mutations), and **Secret**: `ENGINE_A_PASSWORD:
   ✓ present` — the ref *name* and a presence check, never a value. A missing secret is a
   pre-enable failure here, not a first-call surprise.
3. **Probe on demand.** Click **Test connection** on an engine: a strictly **read-only**
   probe (version + capability flags — never a mutating call). This is the same probe a new
   engine must pass before it can ever be enabled.
4. **Watch the SSRF rails refuse.** Click **Add engine** and try a base URL the deployment
   does not allowlist — e.g. `https://example.com/flowable-rest/service` (any id/name,
   any secret ref). Expect a refusal that **names the rule and the next move** — the
   base-URL egress allowlist is deploy config, deliberately not editable in this UI, with
   loopback/link-local/private/metadata ranges denylisted. An engine entry is a URL the
   credential-holding BFF will dial, so runtime registry CRUD is an SSRF surface; trust is
   earned, not asserted. Nothing was persisted by the refusal.
5. **Note the four-eyes section** ("Pending proposals (four-eyes)"): flipping a **prod**
   engine to read-write requires a typed engine id *and* a second, independent
   REGISTRY_ADMIN to approve ("not the proposer and not in the proposer's group"). The demo
   ships `registry-admin-2` exactly so the propose→approve pair can be exercised.

   > **Full onboarding arc — local stack.** The demo's allowlist covers only its own
   > engines, so a real add lands here instead: run the local stack (README "Quick
   > start"), add the optional Flowable 7 engine with
   > `docker compose -f docker/docker-compose.dev.yml --profile flowable-7 up -d`, then in
   > `/admin/engines`: **Add engine (as draft)** with base URL
   > `http://localhost:8083/process-api` (localhost is allowlisted in dev), auth +
   > `password-ref` → the row is born **Draft, read-only** → **Test connection** →
   > **Probed** → **Enable** (reason ≥10, typed engine id; tick "Enable in read-write mode"
   > only when you mean it — on a dev-tagged engine no second approver is needed).
   > **Disable / Remove / Purge** walk the same ladder down: Remove is a soft tombstone
   > (audit references still resolve), Purge is permanent and says so.

6. **Sign in as `access-admin`** and open **Access** (`/admin/access`) — "Access
   administration". Read the sections: **Effective mapping** (group → grant rows: ladder
   grants scoped to engine/tenant, fleet grants marked FLEET), **Add a grant**, **Pending
   proposals (four-eyes)**, and **Access review — who can do what** with its CSV/Markdown
   export (the release-gate artifact for auditors).
7. **Read the governance note** under Add a grant: *a self-widen, any ≥OPERATOR grant with
   a wildcard engine/tenant, and any fleet grant create/remove require a second
   ACCESS_ADMIN to approve* — and the approver may be neither the proposer nor a member of
   the affected group. Removal of fleet grants is governed too: deleting the other apex
   admins would be a takeover, not housekeeping; a ≥2-ACCESS_ADMIN invariant backs it up.

   > **Needs a real IdP.** Group→scope grants only bite for **OIDC** sessions — the demo
   > signs you in through the dev ladder accounts, whose roles are fixed, and it pins
   > `mapping-source: file`, so expect the mapping read-only and every write refused with
   > the pin named (correct behavior, not an error). To exercise the live grant flow —
   > add a grant, see the proposal, approve as a second ACCESS_ADMIN, sign in as the
   > affected user and watch the role change — you need a local deployment with the
   > `oidc` + `db` profiles and an IdP (the repo's CI uses Keycloak;
   > [IDP-SECURITY.md](../IDP-SECURITY.md) §4–§6 is the wiring guide). Mid-incident grant
   > SLA and the file-pin fallback: [RUNBOOK.md](../RUNBOOK.md).

## What you learned

- Three authorities, deliberately orthogonal: engine-ADMIN acts on instances,
  REGISTRY_ADMIN owns the engine registry, ACCESS_ADMIN owns who-can-do-what — and
  break-glass grants none of the fleet powers.
- An engine earns trust through a lifecycle: born draft + read-only, probed with read-only
  calls, enabled by a human — with four-eyes and a typed token before prod mutations.
- The SSRF rails (allowlist + denylist, resolve-then-pin, refusals that name the rule) are
  most of what "runtime registry CRUD" costs, and they are not negotiable from the UI.
- Access widening is a proposal, not an act: four-eyes on anything that grows power,
  presence-only secrets, exports for auditors — the mapping is treated as the most
  dangerous store in the product because it is.

**Next:** [08 — Handover & audit](08-handover-and-audit.md).
