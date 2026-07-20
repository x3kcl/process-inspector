# ⚙️ OPERATIONS — running the Inspector itself

The spec protects the *engines* (do-no-harm); this doc applies the same principles to the
Inspector — the single most credentialed box in the estate and the tool that must be up
during exactly the incidents that take other things down. Register IDs: R-OPS-*, R-AUD-*,
R-SAFE-06/07 (REQUIREMENTS-REGISTER.md).

## 1. Health semantics (R-OPS-01)
- **Liveness** = JVM/process only. **Readiness** = Postgres migrated+reachable, registry
  parsed. **Engine reachability is NEVER a readiness component** — an Inspector with all N
  engines down is READY and serves the triage page with N error envelopes. Engine state
  surfaces only via `/api/engines`, the health strip, and metrics.

## 2. Telemetry (R-OPS-02, R-AUD-04)
> **Implementation status (P1 #12 / Q1, 2026-07-10; issue #96, 2026-07-13).** `/actuator/health`
> (+ liveness/readiness probes) and `/actuator/prometheus` are **live** — actuator +
> micrometer-prometheus are real dependencies. **Emitted (free from the registry):**
> `resilience4j_circuitbreaker_state` + bulkhead metrics, Hikari pool, HTTP-server timings,
> JVM/virtual-thread metrics. **Emitted (issue #96, hand-wired at named sites — verified against a
> real scrape, not assumed):** `audit_insert_failures_total` (tagged `site`), per-engine fan-out
> latency (`engine_fanout_duration_seconds`, tags `engineId`/`leg` — ONE instrumentation site, the
> `GuardedCaller` chokepoint every facade call runs through), `sse_emitters_active` (gauge) +
> `sse_emitter_errors_total` (counter), `bulk_jobs_running` (gauge) + `bulk_item_outcomes_total`
> (counter, tagged `state`, tallied once per finished job). **Structured JSON logs** (gated on the
> `oidc` prod-like profile — dev/test/it-* stay human-readable) **and correlationId MDC
> propagation across the virtual-thread fan-out** (`MdcPropagatingExecutors`, swapped into every
> `Executors.newVirtualThreadPerTaskExecutor()` call site) are **built**. **`GET /api/diag`
> (door gate: ADMIN on at least one engine) is built**: breaker states, cache AGES (triage
> dashboard + leak-views), bulk permit-pool saturation, the last 20 engine-call failures with
> correlationIds, build info (absent outside a `mvn package` build). Per-engine sections
> (breakers/permits/recent-errors) are filtered to engines the caller actually holds ADMIN on —
> same coarse-door/fine-item shape as the operations log (`AuditController#payloadVisible`); a
> per-engine-scoped ADMIN never sees another engine's diagnostics through this endpoint.
> `RecentEngineErrors` truncates captured exception messages to 500 chars (a malformed/wire-shape-
> drifted response can otherwise embed a body snippet via Jackson's deserialization-failure
> message). **Remaining gap, honestly not built:** triage-cache **hit rate**
> (age is emitted; a hit/miss ratio needs `Caffeine.recordStats()` + a Micrometer binder — outside
> issue #96's stated scope) and `/api/diag`'s "targeted per-composite-ID derivation tracing with
> 15-min TTL" (a materially bigger feature layering a cached trace view on the EXISTING
> `EngineCallRecorder`, itself only wired for on-demand "Explain this status" re-derivation today —
> tracked separately, not claimed here).
- `/actuator/prometheus` (auth-gated). Metric set: `audit_insert_failures_total`,
  `engine_fanout_duration_seconds` (tags `engineId`,`leg`), `resilience4j_circuitbreaker_state`,
  `sse_emitters_active` + `sse_emitter_errors_total`, `bulk_jobs_running` +
  `bulk_item_outcomes_total`, Hikari pool, JVM/virtual-thread pinning. Triage-cache **hit rate**
  (as opposed to age, which `GET /api/diag` reports) remains TARGET, not wired.
- Structured JSON logs (the `oidc` profile only — see status note above); every request gets a
  `correlationId` (accept `X-Request-Id` or generate), MDC-propagated across the virtual-thread
  fan-out via `MdcPropagatingExecutors` (a drop-in executor swap — MDC does not auto-inherit,
  confirmed with a dedicated propagation test), logged on every engine call, persisted on every
  audit row, returned in every error envelope and UI toast. Never log secrets or variable
  payloads at INFO.
- ADMIN diagnostics `GET /api/diag` (v1.x): breaker states, cache AGES, permit-pool saturation,
  last-N engine errors with correlationIds, build info. Door gate is "ADMIN on at least one
  engine"; per-engine sections are then filtered to engines the caller holds ADMIN on (see status
  note above). Targeted per-composite-ID derivation tracing with a 15-min TTL is NOT built (see
  status note above).

## 3. Who watches the watcher (R-OPS-03)
The Inspector is probed by **external** monitoring. Alert rules (issue #96,
`deploy/prometheus/alert-rules.yml` — liveness, `AuditInsertFailures`,
`AllCircuitBreakersOpen`, `SseEmitterErrorsSpiking`, and `DatabaseConnectionTimeoutsSpiking`
as the honest proxy for Postgres-unreachable now fire off metrics this app emits;
`InspectorReadinessFailing`/`DiskSpaceHigh` are written but need an exporter this repo does not
deploy — `blackbox_exporter`/`node_exporter` — see `deploy/README.md`), referencing the same
contract metric names: `InspectorDown` (liveness probe), readiness failing
>2m, `audit_insert_failures_total > 0`, Postgres unreachable, disk >80%, SSE emitter errors
spiking, **all circuits open simultaneously** (an inspector-side egress problem, not N engine
failures). No monitoring STACK (Prometheus/Alertmanager) is deployed by this repo's
`docker/*.yml` — the rule file is the contract to hand to whichever one scrapes
`/actuator/prometheus` in a real deploy. Paging route: the workflow platform team. Degraded
mode: design principle 5 IS the fallback — the runbook carries direct-cURL recipes for the top
verbs (see §7).

## 4. Availability & recovery (R-OPS-04)
RTO ≤ 15 min: IaC/one-command redeploy, image pinned by digest, registry YAML + secrets in
version-controlled/secret-managed form. Postgres runs **external to the BFF host** in the
target-state prod topology (never co-fate-shared). Flyway: forward-only, no down-migrations,
backup before migrate; recovery from a bad migration = restore + roll forward.

**Backup posture — honest current state (P0 #4/Q4; PITR tooling #201; Docker-native
#201-followup).** The BFF's Postgres holds the 400-day audit chain (revFADP); its retention +
legal-hold machinery guards data that must not be lost. The shipping mechanism is a **nightly
logical dump** — the `audit-backup` compose service (`docker/docker-compose.demo.yml`,
`pg_dump -Fc` over the network to the named Docker volume `inspector-logical-dumps`,
checksummed, retention-pruned), scheduled **internally** via busybox crond (no host systemd
timer, no host bind-mount). That makes the honest **RPO the schedule interval (24 h)** — this
first closed the "no copy at all" gap (the demo ran on a single docker volume). The restore
drill is **executable, not aspirational**: `deploy/restore-drill.sh` restores the latest dump
from that volume into a throwaway Postgres and asserts `audit_entry` came back partitioned with
its partitions and rows — run it after wiring the backup and on a calendar cadence.
(`deploy/backup-audit-db.sh` + `deploy/systemd/pi-audit-backup.*` — the original host-systemd
mechanism — are superseded for this repo's Compose-based demo; kept for a non-Compose
deployment style, see `deploy/README.md`.)

**Continuous WAL capture / PITR — tooling shipped, not yet activated in prod
(#201-followup).** The other half of a real PITR setup now exists as fully Docker-native code:
the `wal-receiver` compose service streams WAL continuously via `pg_receivewal` (replication
protocol against `postgres:5432`, backed by a permanent physical replication slot so Postgres
retains WAL for however long the receiver is down) into the named volume
`inspector-wal-archive` — replacing issue #201's `archive_mode`/`archive_command` mechanism
entirely (removed from the `postgres` service's config; `pg_receivewal` needs only
`wal_level=replica`). The `audit-basebackup` service takes the weekly PHYSICAL base backup a
WAL replay needs as its anchor (a logical `pg_dump` cannot be replayed forward through WAL —
the two mechanisms are incompatible), to the named volume `inspector-basebackups`. Both connect
over the network with password auth, needing a `pg_hba.conf` override
(`docker/backup/postgres/pg_hba-demo.conf`) the upstream Postgres image doesn't ship by default
for replication connections. `deploy/pitr-drill.sh` proves the whole chain by restoring a base
backup (from the volume) and replaying archived WAL (from the volume, mounted read-only) via
Postgres 16's current recovery mechanism (`recovery.signal` + `restore_command`), either to
"everything available" or an explicit `--target-time`.

**This was rehearsed end-to-end against disposable `docker compose` infrastructure (its own
throwaway Postgres plus the three new services), built and torn down solely for that
verification** — including the property that matters most for a long-lived streaming service:
`wal-receiver` was killed mid-stream, more WAL was generated on the source while it was down,
and it was restarted — resuming with **zero gap and zero duplicate/corrupt segments**
(confirmed via `pg_waldump` on the affected segments and `pg_replication_slots.restart_lsn`
holding the floor throughout the outage). `pitr-drill.sh` was proven in both "replay
everything" and `--target-time` modes against the new volume-based storage, and
`restore-drill.sh` against the new logical-dump volume. **It has deliberately NOT been
activated against the live demo container** — see `deploy/README.md`'s "Activating Docker-native
backups" for the exact (human-run, separately confirmed) turn-on steps. Because the drill has
only run against disposable test infrastructure and not against real accumulated production WAL
history, the honest RPO claim **stays 24 h** until that live drill has actually run — this
section will be updated again once it has.

## 5. Deploys (R-OPS-05, R-SEM-16/17)
`server.shutdown=graceful` (30 s); SIGTERM emits a terminal SSE `shutdown` event (once SSE
exists) so clients reconnect instead of erroring. Deploy policy: single instance, accepted
window ≤2 min, announced in the support channel. Before planned restarts:
`POST /api/admin/drain` refuses new bulk jobs and reports running ones (v1.x). SPA/BFF skew
(**issue #265, built**): nginx serves `/index.html` with `Cache-Control: no-cache` (a stale
cached shell after a redeploy is the root cause of a stale asset manifest) and `/assets/*`
as `public, max-age=31536000, immutable` with a hard `try_files … =404` — a stale tab's
pre-redeploy hashed chunk request never falls through to the SPA shell's 200 (that 200-as-
HTML-for-a-.js-request was the cryptic failure mode). A dynamic-import failure on a lazy
route (`ChunkErrorBoundary`, one `errorElement` per lazy route in `main.tsx`) auto-reloads
the page ONCE — `sessionStorage`-guarded (`chunkErrorRecovery.ts`) so a genuinely broken
deploy can't loop reloads forever — and falls back to the standard `.error-banner` panel on
a second sighting. `/api/meta` + `X-Inspector-Version` skew-mismatch → non-blocking reload
banner remains NOT built (tracked separately from #265).
**Versioned image releases:** pushing a `v*` tag runs `.github/workflows/release.yml` on the
self-hosted runner — builds the two shipping images and publishes each to **both**
`docker.io/x3kcl/process-inspector-{bff,web}` and `ghcr.io/x3kcl/process-inspector-{bff,web}`
in one build (docker/metadata-action lists both names) (semver + `latest` for stable;
prerelease tags like `v1.2.3-rc1` publish only their literal version, never move `latest`,
and mark the GitHub Release as prerelease), then creates the Release with
`docker/docker-compose.release.yml` attached as the consumer quick-start (defaults to Docker
Hub; `PI_REGISTRY=ghcr.io/x3kcl` overrides). Auth: the workflow `GITHUB_TOKEN`
(`packages: write`) for ghcr, plus the `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` repo secrets
(a Read-&-Write Docker Hub access token) for Docker Hub. One-time after first publish: flip
both ghcr packages to public visibility, and set both Docker Hub repos (auto-created on first
push) to Public.
**Continuous edge builds:** every **green** `ci` run on `main` auto-publishes the same two
images to both registries as `:edge` + `:sha-<short7>` (`publish-edge.yml`; `workflow_run`-triggered
so a red main never ships a build — the green-main doctrine applied to publishing). Pinned
release tags are never moved by edge builds.
**Docker Hub overviews:** each Docker Hub repo's Overview (long + short description) is
sourced from `docker/dockerhub/process-inspector-{bff,web}.md` and pushed by
`dockerhub-readme.yml` (peter-evans/dockerhub-description) on any main push that touches those
files or the workflow, and on demand (`workflow_dispatch`). It only rewrites metadata — never
builds an image — and uses the same `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` secrets (the
token needs Read & Write). ghcr derives its package README from the repo, so no sync is needed
there.
**Cutting a version without local git:** Actions → `cut-release` → Run workflow → pick
patch/minor/major. It refuses a `main` HEAD without a green `ci` run, computes the next
semver from the latest *stable* tag (prereleases never advance the baseline), pushes the
annotated tag, and *calls* `release.yml` as a reusable workflow — required because a
`GITHUB_TOKEN`-pushed tag never fires push triggers. Prerelease tags stay manual pushes.

## 6. The audit golden master, operationally (R-AUD-01/02/03)
- **Fail-closed**: tier ≥1 mutations are not issued if the audit INSERT fails; the error
  names Postgres.
- DB role: INSERT/SELECT only (REVOKE UPDATE/DELETE) + guard trigger; per-row hash chain for
  tamper evidence; monthly range partitions; retention default 400 days with an audited
  purge job; legal-hold procedure (suspend purge per engine/tenant/window).
  **Monthly partitioning is live (S5a):** `V10` carved the DEFAULT partition into
  `audit_entry_YYYY_MM` children (append-only never relaxed — the carve moves rows by
  DETACH/CREATE/INSERT/TRUNCATE/ATTACH with `seq`/`chain_hash` verbatim), and
  `AuditPartitionMaintainer` create-aheads the current+next month at startup + daily. Watch for the
  **`AUDIT_DEFAULT_PARTITION_NONEMPTY`** ERROR marker — it means rows are landing in the DEFAULT
  safety net (create-ahead failing, e.g. the connection lacks owner DDL), so the future purge could
  not drop them.
  **The retention purge + legal hold are live (S5b):** the BFF `@Scheduled AuditRetentionPurger`
  drops whole aged-out months via the `SECURITY DEFINER purge_audit()` (a DB-enforced 400-day
  floor + legal-hold + whole-partition-age; the app role has `EXECUTE` only, never raw `DROP`),
  writing a chain-boundary **checkpoint** config event BEFORE each drop (fail-closed). Alerts:
  a **dead-man on the absence of a recent `audit-retention-purge` event** (the earliest signal, long
  before disk fills), and the **`AUDIT_RETENTION_PURGE_ABORTED`** ERROR marker (a purge could not be
  audited, so it did not run). Disable with `inspector.audit.retention-purge.enabled: false` — a
  STOPPED purge is then visible via that dead-man. Legal holds are set/released ADMIN-only at
  `/api/admin/legal-holds` (each a fail-closed audited event); lowering `inspector.audit.retention-days`
  below the 400-day DB floor is refused at boot.
- PII: variable payloads in audit rows are potentially personal data. Per-engine
  `audit-payload: full|redacted|metadata-only`; secret-name denylist → `«redacted»`; payload
  bodies role-gated OPERATOR+; erasure = skeleton-preserving redaction (accountability
  columns + hash chain survive, value columns blank, redaction itself audited). Optional
  registry `jurisdiction`; conflicting jurisdictions ⇒ one inspector per jurisdiction.
- Sizing worksheet maintained (rows/day × payload bytes → disk alert at 80%); Hikari pool
  explicitly sized (default 20) and alerted on exhaustion.

## 7. Threat model (R-OPS-07/08, ARCH §8)
Compromise of the BFF = admin on every registered engine. Mitigations: egress allowlist
(engines + Postgres only); actuator `env`/`configprops`/`heapdump` disabled or sanitized;
**one unique credential per engine** — never shared; secrets as 0400 mounted files where
possible (`password-file` refs re-read per attempt → rotation without restart; a 401 surfaces
as "credential rejected", distinct from unreachable); non-root, read-only-FS container, no
redirects followed on engine calls. Injection: engine/user text is data (no HTML
interpretation — ESLint-enforced), CR/LF-strip + caps on ingest, CSV formula-escape.
**Break-glass** (R-SAFE-06, built v2 — IDP-SECURITY.md §7): sealed local ADMIN account on a
distinct `/break-glass` chain+path that works **when the IdP is down** — reachable via a
directly-known URL (RUNBOOK §4), a plain JS-free HTML form the BFF itself serves. There is
deliberately no auto-surfaced "IdP-unreachable" interstitial: every non-`/api` path is
`authenticated()`, so the SPA can never load pre-auth, and a down IdP fails as the browser's own
native network error on the IdP's foreign origin — no event the BFF's own origin, or the SPA's
JS, ever observes. 4 h session,
distinguished audit flag + alert-on-login + red page banner, mandatory reason ≥10 on every verb
(per-session sticky reason for tier-0 repeats), rotate after use. ADMIN-global but **never**
`ACCESS_ADMIN`/`REGISTRY_ADMIN` — a bricked mapping is recovered via the `INSPECTOR_ACCESS_ADMIN_GROUP`
env grant or the `mapping-source: file` pin (see RUNBOOK), never break-glass. **Audit degrades,
never blocks:** if Postgres is unreachable *concurrent* with the IdP outage, break-glass audit
falls back to a **local tamper-evident append-only file sink** (write-success gates the action) —
the one deliberate fail-closed exception, reconciled to `audit_entry` on recovery.
Session policy (R-SAFE-07, built v2 — IDP-SECURITY.md §5): fixation protection (scoped to
`oidc`/form so the dev Basic-per-XHR SSE isn't orphaned), idle timeout 12 h / absolute 24 h
(**warn-before-guillotine**, never a takeover over a dirty wizard draft), `HttpOnly; Secure;
SameSite=Lax`; OIDC group membership re-evaluated within a **bounded window** — the dangerous set
(tier-3 verbs + bulk + every mapping write) forces a fresh re-authentication (challenge→replay at
verb-intent, never after the confirm token is typed) and resolves grants from the re-authed
principal; bulk jobs survive session expiry, cancel requires a live session. Transport/header
posture (R-OPS-16): app-level `nosniff`/`frame-ancestors`/`Referrer-Policy`/`Permissions-Policy`
always on, **CSP now ENFORCES** (S5 — observation window over; a deploy can set
`csp-report-only:true` to re-observe), the demo nginx MIRRORS the app header set on the SPA
document it serves directly, **HSTS off in the app by default** (the proxy owns it — never
double-emit; the demo Traefik HSTS is a moderate 24 h, no preload), CORS off. Readiness = "mapping loaded (DB rows or file seed)" + a distinct **"mapping resolves
zero effective grants / seed failed" health indicator + alert** (READY alone can't tell seeded-fine
from seeded-to-zero).

## 8. CI gates — the authoritative merge-blocking list (R-OPS-06)
**Landed in `.github/workflows/ci.yml` (merge-blocking):** `lint` (Spotless/palantir) ·
`unit` (backend build + unit suite incl. the **ArchUnit anti-flakiness rule**: `Thread.sleep`
in any test class = build failure) · `frontend` (**ESLint strict-type-checked + Prettier**,
**Vitest**, watermark check, tsc, vite build) · **OpenAPI drift gate** (boots the BFF,
regenerates `schema.d.ts`, `git diff --exit-code`) · `docker` (multi-stage image build) ·
`integration` matrix over **flowable-6 / flowable-7 / legacy** (compose up →
`docker/smoke-test.sh` bounded readiness gate incl. postgres `pg_isready` → `docker/seed.sh`
→ failsafe per-profile IT).
**Landed in `.github/workflows/nightly.yml` (NOT merge-blocking — 02:00 UTC + `workflow_dispatch`):**
a **Testcontainers Postgres/Keycloak suite** (`container-its` job, 14 self-provisioning ITs —
audit partition/retention/roles + fail-closed, shared/team-view governance, mapping/registry
store CRUD, and **a real Keycloak `oidc` leg** — `OidcKeycloakIT`, sealed-login + `max_age`/
`auth_time` semantics a lightweight stub can't exercise) and an **`engine-its` job** (11
mutating corrective-action/flow-surgery/migration/bulk ITs the PR gate skips for the
zero-flake doctrine). A red nightly is a morning-routine triage, not a push gate.
**Genuinely still to land:** Playwright smoke + axe (no CI wiring exists at all yet, tracked
issue #85, blocked on #88's remaining U5 frontend-fitness prerequisite — U1/U2 landed) · Trivy image scan +
SBOM (CycloneDX) + dependency audit in nightly · k6 P1 perf wiring against a seeded
FIX-REF-01 reference dataset (`scripts/perf-scenario-p1.js` exists since the v1 gate but has
never run in CI — tracked issue #93) · a dedicated static WireMock fixture suite for 6.x/7.x
error-JSON shape (partially covered differently today: WireMock proves pure HTTP-client
behavior in `GuardedCallerTest`, and the live `integration` matrix's flowable-6/7/legacy
profiles exercise real error-JSON shape end-to-end — but no standalone fixture suite pins it).
**Where CI runs:** dockerized self-hosted **runner slots** (`docker/ci-runner/`, image =
`myoung34/github-runner` + Maven/python3/jq; zero paid Actions minutes). Harness ports are
fixed remaps, so parallelism is **port-namespace slots**: each runner container carries a
disjoint pre-computed port block as env (engines/PG/BFF per slot; `ci.yml` honors it with
slot-1 fallback, and the frontend job's throwaway Postgres uses a Docker-assigned random
host port). Jobs serialize within a slot, so cross-PR runs AND same-run matrix legs
parallelize across slots collision-free. `scripts/ci-runner.sh ensure` (before every
push/PR — green-ci skill step 0) starts the slots and fails if any FOREIGN (slot-less)
runner is online. Ephemeral (one job per container, fresh registration each restart),
`network_mode: host` (jobs address published ports via localhost), host `docker.sock`
(integration legs drive the shared daemon; trusted-operator model — no fork PRs),
`restart: unless-stopped` for reboot survival. Registration: repo-scoped PAT via
`GITHUB_PERSONAL_ACCESS_TOKEN` env ref, exchanged at start for short-lived tokens.

## 9. Compose profiles
Dev engine profiles in `docker/docker-compose.dev.yml`: **`flowable-6`** (6.8.0 pair,
default via `docker/.env` `COMPOSE_PROFILES`) / **`flowable-7`** (7.1.0, :8083) /
**`legacy`** (6.3.1 pre-cliff, :8084) / **`postgres`** (:5433). Plus **`prod-like`**
(release image +
Postgres + OIDC stub + reverse proxy with SSE buffering off, forwarded headers on) — the
M4 dual-auth path, Flyway, and proxy behavior are exercised before prod, not in it.
Secrets per target: compose `.env` mode 600 gitignored; k8s `Secret` via `envFrom`.
Container sizing: requests 0.5 CPU/1Gi, limits 2 CPU/2Gi (revisit at M6 load test),
`-XX:MaxRAMPercentage=75`, OOM heap dump to a mounted volume.

## 10. Runbook index (docs/RUNBOOK.md — release-gate deliverable, R-GOV-02)
Restart procedure incl. INTERRUPTED-job recovery · deploy checklist (drain → window →
verify) · restore drill script · "inspector down / inspector suspect": where to curl from
(only hosts inside the ARCH §6 fence), break-glass credential procedure, the REST Parity
Appendix (R-L3-02), and the warning that direct engine mutations bypass the audit golden
master and must be hand-logged in the ticket.
