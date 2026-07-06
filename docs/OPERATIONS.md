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
- `/actuator/prometheus` (auth-gated). Contract metric set: per-engine fan-out latency
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
The Inspector is probed by **external** monitoring. Shipped alert rules (files in `deploy/`,
referencing the contract metric names): `InspectorDown` (liveness probe), readiness failing
>2m, `audit_insert_failures_total > 0`, Postgres unreachable, disk >80%, SSE emitter errors
spiking, **all circuits open simultaneously** (an inspector-side egress problem, not N engine
failures). Paging route: the workflow platform team. Degraded mode: design principle 5 IS the
fallback — the runbook carries direct-cURL recipes for the top verbs (see §7).

## 4. Availability & recovery (R-OPS-04)
RTO ≤ 15 min: IaC/one-command redeploy, image pinned by digest, registry YAML + secrets in
version-controlled/secret-managed form. RPO ≤ 5 min: Postgres WAL archiving/PITR; Postgres
runs **external to the BFF host** in prod (never co-fate-shared). Flyway: forward-only, no
down-migrations, automated backup gate before migrate; recovery from a bad migration =
restore + roll forward. Restore drill quarterly — the audit golden master's backup is
verified as part of it.

## 5. Deploys (R-OPS-05, R-SEM-16/17)
`server.shutdown=graceful` (30 s); SIGTERM emits a terminal SSE `shutdown` event (once SSE
exists) so clients reconnect instead of erroring. Deploy policy: single instance, accepted
window ≤2 min, announced in the support channel. Before planned restarts:
`POST /api/admin/drain` refuses new bulk jobs and reports running ones (v1.x). SPA/BFF skew:
`index.html` no-cache, hashed assets immutable, `/api/meta` + `X-Inspector-Version`,
mismatch → non-blocking reload banner; dynamic-import failure → reload prompt.

## 6. The audit golden master, operationally (R-AUD-01/02/03)
- **Fail-closed**: tier ≥1 mutations are not issued if the audit INSERT fails; the error
  names Postgres.
- DB role: INSERT/SELECT only (REVOKE UPDATE/DELETE) + guard trigger; per-row hash chain for
  tamper evidence; monthly range partitions; retention default 400 days with an audited
  purge job; legal-hold procedure (suspend purge per engine/tenant/window).
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
**Break-glass** (R-SAFE-06): sealed local ADMIN account, `/break-glass` path, 4 h session,
distinguished audit flag + alert + page banner, rotate after use.
Session policy (R-SAFE-07): fixation protection, idle timeout 12 h / absolute 24 h,
`HttpOnly; Secure; SameSite=Lax`; OIDC claims re-evaluated ≤15 min, tier-3/4 force
re-validation; bulk jobs survive session expiry, cancel requires a live session.

## 8. CI gates — the authoritative merge-blocking list (R-OPS-06)
1. Backend build + unit suite · 2. WireMock contract tests incl. **6.x AND 7.x error-JSON
fixtures** · 3. Testcontainers Postgres suite (M4+) · 4. Spotless + ESLint strict ·
5. OpenAPI export + `git diff --exit-code` · 6. Vitest · 7. Playwright smoke (≤10 min,
incl. axe) · 8. Image build + Trivy scan (fixable HIGH/CRIT fail).
Nightly (release-blocking, not merge-blocking): full engine-matrix Playwright, P1/P2 perf
scenarios, capability matrix cross. Weekly: image rescan; monthly: base-image rebuild; SBOM
(CycloneDX) attached to releases.

## 9. Compose profiles
Dev engine profiles (6.x current / pre-6.4 / 7.x) + **`prod-like`** (release image +
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
