# 🔐 DATA CLASSIFICATION — what the Inspector handles, and under which rules

Release-gate deliverable (SPEC §13 — "data-classification one-pager approved"). Normative
sources: SPEC §9 (R-AUD-03), OPERATIONS §6–§7, ARCHITECTURE §8. This document is
engineering guidance for classification and controls; it is **not legal advice** — the
operating organization's DPO signs off on the legal reading.

## 1. Data inventory & classification

| Data class | Examples | Where it lives | Classification | Persisted by the Inspector? |
|---|---|---|---|---|
| **Engine credentials** | `inspector-svc` Basic-auth passwords, one per engine | Env vars / mounted secret files only (`password-ref: ENGINE_A_PASSWORD`) | **Secret** | **Never.** Config holds *references*; values come from the environment or 0400-mounted files, re-read per attempt (rotation without restart). Never in config values, logs, API responses, or audit rows. CI-enforced by `scripts/security-audit.sh`. |
| **Process variables** | Order payloads, customer names, addresses, account and case data — whatever the business processes carry | The **engines** own them; the Inspector reads them over REST and displays them | **Confidential, potentially sensitive PII** (revFADP / GDPR — see §2) | **Transiently no** (display path is read-through, previews capped at 8 KiB). **Selectively yes**: old/new values inside `edit-variable` audit payloads, and whatever operators paste into notes. That selective persistence is exactly what §3 governs. |
| **Audit log** | Actor, timestamp, engine/instance, verb, reason, ticketId, action payload, outcome | Inspector's Postgres (`audit_entry`, monthly partitions) | **Confidential** — contains *employee* personal data (who did what when) **and** may embed process-variable PII in payloads | **Yes — deliberately.** It is the accountability golden master ([AUDIT-ATTRIBUTION.md](AUDIT-ATTRIBUTION.md)); retention and erasure per §3. |
| **Instance notes** | Free-text handover notes per composite ID | Inspector's Postgres | **Confidential, potentially PII** (free text — operators may write anything) | **Yes.** Same retention/redaction regime as audit payloads. |
| **Incident ledger — failure classes** | `incident`: signature hash, normalized error message (R-SEM-03-sanitized, literals → `#`), ONE sample raw member message, lifecycle state, per-engine count blob | Inspector's Postgres (`incident`) | **Confidential** — `sample_raw_message` is the very string the Stage-0 group card renders (same classification): sanitized upstream, but an engine may embed business identifiers in exception text; no operator personal data | **Yes — deliberately** (the ledger IS the memory, R-BAU-10). Rows persist (bounded by distinct failure classes); a compliance purge would reuse the registry-delete doctrine if ever demanded. |
| **Incident episodes** | `incident_episode`: started/ended timestamps, peak count, `resolved_by`, free-text `resolve_reason`, `ticket_id` | Inspector's Postgres (`incident_episode`) | **Confidential** — *employee* personal data (who resolved what, when) plus free text under the §4 handling rules (no secrets/customer records in reasons) | **Yes.** MTTR / post-incident-review substrate (S3 writes the resolve columns); transitions additionally audited as config events. |
| **Incident occurrences** | `incident_occurrence`: per-bucket failing counts + truncation flags per incident | Inspector's Postgres (monthly partitions) | **Internal** — operational telemetry, no personal data | **Yes**, 400-day drop-partition retention (`inspector.incidents.retention-days`, aligned with `triage_snapshot`). |
| **Operator identities** | OIDC subject, name, roles, scope grants | IdP; BFF session + audit rows; group→scope mapping file | **Internal / personal data** | Audit rows only (that's their purpose). |
| **Forwarded actor (egress)** | `X-Forwarded-User: <employee>` sent to `forward-user` engines on mutating calls (SPEC §9, ARCH §6, M4-CLOSEOUT §2) | Leaves the BFF over the wire to the engine; **never persisted by the Inspector** | **Internal / personal data** (employee identity) — engine-side logs may retain it | **Never persisted here.** Off by default, opt-in per engine; equals the audit-row actor (break-glass namespaced); trusted only over an authenticated/isolated channel; the identity-forwarding egress path must not log request headers (D2f). |
| **Operational telemetry** | Metrics, structured logs, correlationIds | Prometheus / log pipeline | **Internal** | Logs never carry secrets or variable payloads at INFO (OPERATIONS §2). |

The Inspector's threat posture follows from this table: the BFF is *the single most
credentialed box in the estate* (admin-equivalent REST credentials to every registered
engine) and its Postgres concentrates attribution data. Compromise controls: egress
allowlist, sanitized actuator, non-root read-only-FS container, unique credential per
engine (OPERATIONS §7).

## 2. Process variables under the revFADP (and GDPR)

The revised Swiss Federal Act on Data Protection (**revFADP**, in force 1 September 2023)
applies to the personal data that business processes carry in their variables — customer
records, orders, health or financial case data. Under revFADP definitions, some of this is
**sensitive personal data** (Art. 5 lit. c: health, ideological/religious views, social
security measures, etc.), and processes routinely qualify. Where EU data subjects are in
scope, the GDPR applies in parallel; the controls below are designed to satisfy both.

**Roles:** the operating organization is the controller; the Inspector is an internal
processing tool inside that organization's responsibility. It creates **no new collection**
of personal data — but it creates new *copies* (audit payloads, notes) and new *access
paths* (operator screens), and those are what must be governed.

| revFADP obligation | Inspector mechanism |
|---|---|
| **Proportionality / data minimization** (Art. 6) | Read-through display, no variable warehouse; previews capped (8 KiB, full value on explicit demand, ≤5 MiB); audit payloads configurable **per engine**: `audit-payload: full \| redacted \| metadata-only` — engines carrying sensitive-category data run `redacted` or `metadata-only`. |
| **Data security** (Art. 8) | TLS to engines and clients; RBAC — audit **payload bodies are role-gated OPERATOR+**; secret-name denylist renders `«redacted»`; append-only DB role + hash-chain tamper evidence; secrets never persisted. |
| **Records of processing activities** (Art. 12) | This document + the registry (which engines, which environments, which `audit-payload` mode, optional `jurisdiction` tag) constitute the Inspector's entry in the controller's processing record. |
| **Right of access** (Art. 25) | Audit rows and notes are queryable by engine/instance/actor/time on the `/audit` page — a subject-access search over Inspector-held copies is a filtered export, not a forensic project. |
| **Erasure / rectification** | **Skeleton-preserving redaction** (§3): value columns blanked, accountability columns and hash chain survive, the redaction itself audited. Satisfies erasure of the personal data without destroying the integrity of the audit trail. |
| **Retention limits** | 400-day default with **audited purge**; legal-hold suspends purge per engine/tenant/window (§3). |
| **Breach notification** (Art. 24, to the FDPIC) | `audit_insert_failures_total`, tamper-evidence hash chain, and access logging give the detection substrate; the notification duty and its clock belong to the controller's incident process. |
| **DPIA** (Art. 22) | Onboarding an engine whose processes carry sensitive-category data at `audit-payload: full` warrants a DPIA before mutation rights are enabled (the read-only-first onboarding ramp, R-GOV-04, is the natural checkpoint). |
| **Cross-border disclosure** (Art. 16–17) | The registry's optional `jurisdiction` field is the guard: **engines under conflicting jurisdictions get separate Inspector deployments** — one inspector per jurisdiction, never one inspector spanning them (OPERATIONS §6). Inspector Postgres is deployed in the same jurisdiction as the data it copies. |

Practical defaults: **prod engines with personal data → `audit-payload: redacted`**;
`full` is reserved for engines whose variable content is reviewed as non-sensitive, or
where the incident-forensics value is explicitly judged to outweigh minimization and the
DPO has signed off.

## 3. Retention, purge, redaction, erasure

- **Retention: 400 days** (default, configurable) for audit rows and notes — chosen to
  cover a full annual audit cycle plus follow-up. Rationale: the audit log is the *only*
  human-attribution record (the engines attribute everything to `inspector-svc`), so it is
  intentionally retained longer than typical operational logs.
- **Purge is itself audited** (implemented, S5b): expiry is a whole-**month partition drop**
  via the `SECURITY DEFINER purge_audit()` function. The BFF orchestrator writes a
  **chain-boundary checkpoint** config event (the `chain_hash` + `seq` of the last dropped and
  first surviving row, so the tamper chain stays verifiable across the gap) **before** each
  drop — fail-closed: if the audit write fails, nothing is dropped. A started/terminal event
  pair records the run. The function enforces a **hard 400-day floor in the DB** (a compromised
  caller cannot purge younger data) and the app role holds `EXECUTE` only, never raw `DROP`.
  *Because drops are whole-partition, effective retention is up to 400 days + one partition
  width, and a hold over-retains unrelated subjects sharing that month (DP MAJOR-5) — row-scoped
  enforcement is a v-next; the exposure is accepted here.*
- **Legal hold** (implemented, S5b): purge is suspended per engine / tenant / time window; the
  hold is **enforced by the DB inside `purge_audit()`** (not merely consulted by a cooperating
  job), and both set and release are fail-closed audited events carrying the human actor
  (OPERATIONS §6).
- **Erasure = skeleton-preserving redaction**: on a validated erasure request, payload and
  free-text value columns are blanked while `actor`, `ts`, `engineId`, `instanceId`,
  `action`, `outcome` and the hash chain survive. The redaction writes its own audit row.
  We never physically delete individual audit rows — that would break both the hash chain
  and the accountability guarantee; redaction removes the *personal data*, not the *fact
  that an action occurred*.
- **Secret-name denylist**: variable names matching the denylist (passwords, tokens, keys)
  are stored and rendered as `«redacted»` in audit payloads regardless of the per-engine
  payload mode.
- **Backups** inherit classification: Postgres PITR/WAL archives contain everything above —
  same jurisdiction, same access restrictions; the quarterly restore drill (OPERATIONS §4)
  verifies the golden master's backup without creating uncontrolled copies.

## 4. Handling rules for humans

- Never paste secrets or full customer records into **notes**, **reasons**, or the
  **ticketId** — all are retained 400 days and visible to every operator with instance access;
  the ticketId is additionally hash-chain-covered and never redacted by the payload modes (it is
  a short accountability handle — the `ticket-id-pattern` guard, R-AUD-07, is the enforcement
  point). Reference the ticket, don't inline the data.
- **Copy-for-ticket** output and shift-report exports leave the Inspector's control the
  moment you paste them — the ticket system's classification rules take over. Don't export
  variable *values* into tickets unless the ticket system is cleared for them.
- Credential handling: engine passwords exist only as env refs / mounted files. If you find
  a literal password anywhere in config, logs, or a repo, that is an incident (rotate,
  then fix) — `scripts/security-audit.sh` fails CI on the patterns we know how to detect.
