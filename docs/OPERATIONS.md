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
> **Implementation status (P1 #12 / Q1, 2026-07-10).** `/actuator/health` (+ liveness/readiness
> probes) and `/actuator/prometheus` are now **live** — actuator + micrometer-prometheus are real
> dependencies (before this they 404'd). **Emitted today:** `resilience4j_circuitbreaker_state`
> and bulkhead metrics (the breaker/lane signal), Hikari pool, HTTP-server timings, and JVM /
> virtual-thread metrics — all for free from the registry. The **named application-metric contract
> below is the TARGET, not yet wired** (per-engine fan-out latency, triage-cache hit rate, SSE
> gauges, bulk-job counters, `audit_insert_failures_total`) — each needs a Micrometer counter/timer
> at its call site (tracked follow-up); until then the interim signal for those is the audit event /
> log line. **Structured-JSON logs + `correlationId` MDC and `GET /api/diag` are likewise not yet
> built.** No fiction: treat the un-emitted items as roadmap, not shipped.
- `/actuator/prometheus` (auth-gated). Target contract metric set: per-engine fan-out latency
  histograms (tags `engineId`,`leg`), `resilience4j_circuitbreaker_state`, triage-cache hit
  rate, SSE emitter gauge + error counter, bulk-job gauges + per-item outcome counters,
  `audit_insert_failures_total`, Hikari pool, JVM/virtual-thread pinning.
- Structured JSON logs; every request gets a `correlationId` (accept `X-Request-Id` or
  generate), MDC-propagated across the virtual-thread fan-out (task decorator — MDC does not
  auto-inherit), logged on every engine call, persisted on every audit row, returned in
  every error envelope and UI toast. Never log secrets or variable payloads at INFO.
- ADMIN diagnostics `GET /api/diag` (v1.x): breaker states+history, cache ages, semaphore
  saturation, last N engine errors with correlationIds, build info; targeted per-composite-ID
  derivation tracing with 15-min TTL.

## 3. Who watches the watcher (R-OPS-03)
The Inspector is probed by **external** monitoring. Alert rules (TARGET — the rule files under
`deploy/` are **not yet shipped**; the liveness/readiness probes they'd bind to ARE live as of
P1 #12, the custom-metric rules wait on §2's metric wiring), referencing the contract metric
names: `InspectorDown` (liveness probe), readiness failing
>2m, `audit_insert_failures_total > 0`, Postgres unreachable, disk >80%, SSE emitter errors
spiking, **all circuits open simultaneously** (an inspector-side egress problem, not N engine
failures). Paging route: the workflow platform team. Degraded mode: design principle 5 IS the
fallback — the runbook carries direct-cURL recipes for the top verbs (see §7).

## 4. Availability & recovery (R-OPS-04)
RTO ≤ 15 min: IaC/one-command redeploy, image pinned by digest, registry YAML + secrets in
version-controlled/secret-managed form. Postgres runs **external to the BFF host** in the
target-state prod topology (never co-fate-shared). Flyway: forward-only, no down-migrations,
backup before migrate; recovery from a bad migration = restore + roll forward.

**Backup posture — honest current state (P0 #4 / Q4).** The BFF's Postgres holds the 400-day
audit chain (revFADP); its retention + legal-hold machinery guards data that must not be lost.
The shipping mechanism is a **nightly logical dump** — `deploy/backup-audit-db.sh` (`pg_dump -Fc`
to a second-disk `PI_BACKUP_DIR`, checksummed, retention-pruned), scheduled by
`deploy/systemd/pi-audit-backup.timer`. That makes the honest **RPO the timer interval (24 h)**,
not the ≤5-min WAL/PITR previously claimed here — continuous WAL archiving/PITR is a documented
follow-up; this first closes the "no copy at all" gap (the demo ran on a single docker volume).
The restore drill is now **executable, not aspirational**: `deploy/restore-drill.sh` restores the
latest dump into a throwaway Postgres and asserts `audit_entry` came back partitioned with its
partitions and rows — run it after wiring the backup and on a calendar cadence.

## 5. Deploys (R-OPS-05, R-SEM-16/17)
`server.shutdown=graceful` (30 s); SIGTERM emits a terminal SSE `shutdown` event (once SSE
exists) so clients reconnect instead of erroring. Deploy policy: single instance, accepted
window ≤2 min, announced in the support channel. Before planned restarts:
`POST /api/admin/drain` refuses new bulk jobs and reports running ones (v1.x). SPA/BFF skew:
`index.html` no-cache, hashed assets immutable, `/api/meta` + `X-Inspector-Version`,
mismatch → non-blocking reload banner; dynamic-import failure → reload prompt.
**Versioned image releases:** pushing a `v*` tag runs `.github/workflows/release.yml` on the
self-hosted runner — builds the two shipping images and publishes them to
`ghcr.io/x3kcl/process-inspector-{bff,web}` (semver + `latest` for stable; prerelease tags
like `v1.2.3-rc1` publish only their literal version, never move `latest`, and mark the
GitHub Release as prerelease), then creates the Release with
`docker/docker-compose.release.yml` attached as the consumer quick-start. Auth is the
workflow `GITHUB_TOKEN` (`packages: write`) — no extra secret. One-time after first publish:
flip both ghcr packages to public visibility.
**Continuous edge builds:** every **green** `ci` run on `main` auto-publishes the same two
images as `:edge` + `:sha-<short7>` (`publish-edge.yml`; `workflow_run`-triggered so a red
main never ships a build — the green-main doctrine applied to publishing). Pinned release
tags are never moved by edge builds.
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
distinct `/break-glass` chain+path that works **when the IdP is down** (reachable from an
inspector-owned "Identity provider unreachable" interstitial — no memorized URL), 4 h session,
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
always on, CSP report-only-first, **HSTS off by default** (the proxy owns it — never double-emit),
CORS off. Readiness = "mapping loaded (DB rows or file seed)" + a distinct **"mapping resolves
zero effective grants / seed failed" health indicator + alert** (READY alone can't tell seeded-fine
from seeded-to-zero).

## 8. CI gates — the authoritative merge-blocking list (R-OPS-06)
**Landed in `.github/workflows/ci.yml`:** `lint` (Spotless/palantir) · `unit` (backend
build + unit suite incl. the **ArchUnit anti-flakiness rule**: `Thread.sleep` in any test
class = build failure) · `frontend` (watermark check + tsc + vite build) · `docker`
(multi-stage image build) · `integration` matrix over **flowable-6 / flowable-7 / legacy**
(compose up → `docker/smoke-test.sh` bounded readiness gate incl. postgres `pg_isready` →
`docker/seed.sh` → failsafe per-profile IT).
**Still to land:** WireMock contract tests with **6.x AND 7.x error-JSON fixtures** ·
Testcontainers Postgres suite (M4+) · ESLint strict · OpenAPI export +
`git diff --exit-code` · Vitest · Playwright smoke (≤10 min, incl. axe) · Trivy scan
(fixable HIGH/CRIT fail) · **a real Keycloak `oidc`/prod-like leg** (v2 IdP-Security — a
lightweight OIDC stub omits `max_age`/refresh/overage, exactly the security-critical semantics;
budget its realm-import boot against the 20-min integration cap or split to its own job).
Nightly (release-blocking, not merge-blocking): full engine-matrix Playwright, P1/P2 perf
scenarios, capability matrix cross. Weekly: image rescan; monthly: base-image rebuild; SBOM
(CycloneDX) attached to releases.
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
