# Incident Ledger — persisted failure-class lifecycle & history (R-BAU-10)

Status: DESIGN v2 (post-panel) · 2026-07-18 · v2 demand-driven track

## 0. Provenance

Drafted 2026-07-18 from a current-state codebase survey; panel-reviewed the same day by
three independent models with distinct lenses, all returning **APPROVE-WITH-CHANGES**:

| Seat | Model | Headline findings (adopted) |
|---|---|---|
| Architecture | Gemini 3 Flash (Pro tier was quota-blocked) | "Zombie incident" regression race → post-resolve zero-state requirement; optimistic locking; event-decoupled ingestion; partition-maintainer coverage; truncation-honest sparklines; algo-generation UI filter |
| Data model / scale | OpenAI o3 | occurrence PK must be the business key `(incident_id, sampled_at)`; lost-update races → `@Version` + conditional UPDATE; keep FK (pre-created empty partitions); JSONB churn = monitor-only at this cardinality; service-layer scope filter acceptable |
| Product / ops | OpenAI GPT-5-mini (GPT-5 quota-blocked) | episode records are mandatory for per-episode MTTR; regression needs threshold/hysteresis, not any-nonzero; resolve should offer explicit opt-in "also acknowledge"; fleet-wide identity confirmed with per-engine visibility inside |

Explicitly rejected/deferred panel asks, with reasons: assignee/severity fields, external
alert/deploy correlation, auto-resolve policies, reporting/CSV dashboard (scope creep for
v1 of a troubleshooting BFF — recorded in §11 as candidate follow-ups); DB-side JSONB scope
filtering (v1 list is bounded/unpaginated; service-layer projection matches the existing
`TriageScopeProjector` doctrine — revisit only if incident cardinality grows).

Prior-art note: DESIGN-REVIEW's "3am seat" doctrine — *triage happens by failure class,
not time-windowed incident objects* — is honored: an incident IS a failure class's
persisted lifecycle, never a new grouping axis.

## 1. Problem

Stage 0 already answers *"what is failing right now, grouped by root cause"* — the R-SEM-03
`ErrorSignatureNormalizer` fingerprint feeds in-memory `ErrorGroup` cards, ack noise-control
(R-BAU-01, `error_group_ack`), and cluster-scoped bulk retry (`POST /api/bulk/error-class`).

What the product cannot answer today:

- **History evaporates.** Groups are recomputed per poll (~20s cache). The moment a DLQ is
  drained, every trace of the incident vanishes. There is no record that "payment-gateway
  timeout killed 4,300 instances on 2026-07-14, 14:02–15:40."
- **No arrival-rate timeline.** A spike (outage) and a slow trickle (data-quality bug) look
  identical: a card with a count.
- **No lifecycle.** Ack is deliberately *state, not history* (V15). Nothing represents "we
  fixed this" — so nothing can flag a **regression** or measure time-to-resolution.

Sentry proved fingerprint-keyed issues-with-a-lifecycle are the highest-leverage triage
abstraction for application errors; no BPM-space tool has it. Secondary payoff: issue #106
(remediation playbooks) stalled partly because audit rows lack the *triggering error
signature*; a persisted incident keyed by signature is the missing join point.

## 2. What already exists (REUSED, not rebuilt)

| Capability | Where | Reused as |
|---|---|---|
| Fingerprint (normalize + SHA-256 + `ALGO_VERSION`) | `ErrorSignatureNormalizer` (R-SEM-03) | incident identity key, verbatim |
| Cross-engine grouping | `TriageAggregationService.aggregate()` → `ErrorGroup` | the ONLY ingestion input — zero new engine calls |
| Ack + auto-resurface baseline | `error_group_ack` (V15, R-BAU-01) | untouched; complementary (§7) |
| Cluster-scoped bulk retry | `BulkErrorClassService`, `POST /api/bulk/error-class` | the "Retry all" action, verbatim |
| Background engine budget | `GuardedCaller.CallPriority.BACKGROUND` sampler lane | inherited via piggyback ingestion (§5) |
| Partitioned time-series + drop-partition retention | `triage_snapshot` (V5) + `SnapshotPartitionMaintainer` | `incident_occurrence` pattern; maintainer EXPLICITLY extended to the new table |
| Config-event audit (R-AUD-10) | ack/registry precedent | resolve/reopen/regression transitions |

## 3. Data model (Flyway V18 — next free version)

Three tables, BFF-own Postgres, `ddl-auto=validate`, schema from Flyway only.

### 3.1 `incident` — identity + live state (long-lived, mutable)

```sql
CREATE TABLE incident (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signature_hash     text NOT NULL,
    algo_version       int  NOT NULL,              -- R-SEM-03 binding contract, as in acks
    exception_class    text,                        -- nullable (no stacktrace refinement)
    normalized_message text NOT NULL,
    sample_raw_message text NOT NULL,
    state              text NOT NULL CHECK (state IN ('OPEN','RESOLVED','REGRESSED')),
    first_seen         timestamptz NOT NULL,
    last_seen          timestamptz NOT NULL,
    last_total         bigint NOT NULL,             -- lower bound when truncated
    last_truncated     boolean NOT NULL,
    counts_by_engine   jsonb NOT NULL,              -- latest engineId → "defKey:vN" → count (display blob, no GIN)
    seen_zero_since_resolve boolean NOT NULL DEFAULT false,  -- regression zero-state gate (§5)
    regression_count   int NOT NULL DEFAULT 0,
    last_regressed_at  timestamptz,
    version            bigint NOT NULL DEFAULT 0,   -- JPA @Version optimistic lock
    CONSTRAINT uq_incident UNIQUE (signature_hash, algo_version)
);
CREATE INDEX idx_incident_state ON incident (state, last_seen DESC);
```

- Identity is **fleet-wide** `(signature_hash, algo_version)` — one root cause = one card
  (panel-confirmed P1); per-engine/definition visibility lives in `counts_by_engine`.
- An `ALGO_VERSION` bump orphans old-generation rows exactly like acks (needs-re-binding
  doctrine): UI defaults to the current generation with an "archived generations" toggle.
- Mutable state ⇒ no guard trigger; lifecycle *history* = episodes (3.2) + config-event
  audit rows. **All transitions run under optimistic locking** (`version`); the sampler's
  transition UPDATEs are additionally state-conditional (`... WHERE state = :expected`) so
  an interleaved resolve/reopen makes the sampler's write miss rather than clobber.

### 3.2 `incident_episode` — one row per open→resolve cycle (the MTTR substrate)

```sql
CREATE TABLE incident_episode (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id    bigint NOT NULL REFERENCES incident(id),
    start_state    text NOT NULL CHECK (start_state IN ('OPEN','REGRESSED')),
    started_at     timestamptz NOT NULL,
    peak_total     bigint NOT NULL DEFAULT 0,       -- max observed live total this episode
    ended_at       timestamptz,                     -- NULL while episode is live
    resolved_by    text,
    resolve_reason text CHECK (resolve_reason IS NULL OR char_length(resolve_reason) >= 10),
    ticket_id      text
);
CREATE INDEX idx_incident_episode_incident ON incident_episode (incident_id, started_at DESC);
```

- Opened on first sighting and on each REGRESSED transition; closed by resolve (which
  stamps `ended_at`/`resolved_by`/`resolve_reason`/`ticket_id`). Exactly one live
  (NULL-`ended_at`) episode per non-RESOLVED incident — enforced in service logic, asserted
  by tests. Per-episode MTTR = `ended_at − started_at`; reopen (human "resolved by
  mistake") reopens the last episode (clears `ended_at`, audited) rather than minting one.
- Resolve metadata lives HERE, not on `incident` (panel: episode records are the
  post-incident-review substrate; `incident` stays a small hot row).

### 3.3 `incident_occurrence` — narrow time-series (sparkline/timeline)

```sql
CREATE TABLE incident_occurrence (
    incident_id       bigint NOT NULL REFERENCES incident(id),
    sampled_at        timestamptz NOT NULL,
    total             bigint NOT NULL,
    dead_letter_count bigint NOT NULL,
    retrying_count    bigint NOT NULL,
    truncated         boolean NOT NULL,
    PRIMARY KEY (incident_id, sampled_at)            -- business key IS the PK (panel: o3 BLOCKER fix)
) PARTITION BY RANGE (sampled_at);
CREATE TABLE incident_occurrence_default PARTITION OF incident_occurrence DEFAULT;
```

- No surrogate id (a `(id, sampled_at)` PK would not be unique across partitions and
  invites unsafe id-only references). JPA maps the composite key (`@EmbeddedId`).
- Monthly partitions create-ahead (while EMPTY — zero-cost FK validation) + drop-behind at
  400 days, by extending the `SnapshotPartitionMaintainer` mechanism to this table
  (explicit slice-1 deliverable + startup next-month existence check; a missed rotation
  lands rows in the DEFAULT partition, never fails inserts).
- FK kept (panel P4): `incident` rows are never deleted, partition DROP is metadata-only.
- Idempotent upsert `ON CONFLICT (incident_id, sampled_at) DO UPDATE`, bucket-floored like
  `SnapshotBucket`. A poll is not a mutation: no corrective-action rails.

## 4. JSONB churn note (accepted risk)

`counts_by_engine` is rewritten each cycle per live incident. At expected cardinality
(tens–hundreds of live groups × 60s) this is &lt;few MB/day of WAL — monitor
`pg_total_relation_size('incident')` via the existing postgres-mcp dev tooling; if growth
ever matters, the escape hatch is moving breakdowns into occurrence rows. Not v1 work.

## 5. Ingestion — piggyback + event seam, do-no-harm

The triage aggregation already runs on the 2-permit BACKGROUND lane once per
`inspector.snapshot.sample-interval` (60s). A second scheduler would double background
engine load whenever it missed the ~20s triage cache. Therefore:

- `SnapshotSource.sample()` widens to return an `AggregationSample(laneCounts, errorGroups,
  sampledAt)` (the lane counts' and groups' single aggregation pass).
- `SnapshotSampler` keeps its snapshot-store write, then **publishes a synchronous Spring
  `AggregationSampledEvent`** carrying the sample. `IncidentLedgerService` is an
  `@EventListener` gated by `inspector.incidents.enabled` (default true, independent of
  `inspector.snapshot.enabled` consumers-side; sampler off ⇒ both stores idle — documented).
  Event-decoupling (panel) keeps the sampler ignorant of the ledger and gives the ledger
  its own failure isolation: a ledger exception is caught + warn-once, never fails the cycle.
- **Zero additional engine calls.** The ledger is a pure DB-side consumer.

Per live group per cycle (all in one transaction, optimistic-locked):

1. No `(hash, algoVersion)` row → INSERT `incident` state=OPEN + open an episode
   (start_state=OPEN).
2. OPEN/REGRESSED row → update `last_seen/last_total/last_truncated/counts_by_engine`,
   bump live episode `peak_total`.
3. RESOLVED row → **regression gate**: transition to REGRESSED (+ new episode
   start_state=REGRESSED, `regression_count++`, config-event audit row recording the
   triggering count) only if BOTH:
   - `seen_zero_since_resolve` is true — i.e. at least one post-resolve cycle observed the
     group absent or zero (kills the cache/retry-lag "zombie incident": a fresh resolve
     cannot instantly regress), AND
   - live total ≥ `inspector.incidents.regression-min-count` (default 1; configurable
     hysteresis per panel).
   While the gate is closed, the cycle still updates `last_seen`/totals/occurrence (the
   data stays honest; only the state transition waits).
4. Always: upsert the bucketed `incident_occurrence` row.

Absent groups: write nothing — except that for RESOLVED incidents an absent/zero group sets
`seen_zero_since_resolve = true` (the one deliberate absence-triggered write). "Quiet" is
DERIVED at read time (`last_seen < now − inspector.incidents.quiet-window`, default 24h),
never stored. Down engines → absent from aggregation → gaps, no fabricated zeros (their
groups' totals may dip; the occurrence rows record what was observed — same honesty rule as
the snapshot store). Store down → warn once + skip.

## 6. API surface (springdoc-scanned; records; RFC-7807; no delete — the ledger is history)

| Route | Floor | Semantics |
|---|---|---|
| `GET /api/incidents?state=&window=` | VIEWER | bounded list (no pagination v1), sections derivable client-side; `quiet` derived; algo-generation split; **R-SAFE-17 scoping**: service-layer projection filtering `counts_by_engine` to the caller's engine scope, zero-intersection incidents omitted (TriageScopeProjector doctrine) |
| `GET /api/incidents/{id}` | VIEWER | ledger row + episodes + windowed occurrence series (clamped like `/api/triage/trends`) + LIVE `ErrorGroup` join at render (incl. read-only ack state) |
| `POST /api/incidents/{id}/resolve` | OPERATOR | body `{reason ≥10, ticketId?, alsoAcknowledge?}`; closes the live episode, state→RESOLVED, resets `seen_zero_since_resolve=false`; config-event audit. `alsoAcknowledge=true` additionally invokes the EXISTING ack flow per involved engine×definitionKey as a second, separately-audited action (explicit opt-in checkbox in UI — panel P3) |
| `POST /api/incidents/{id}/reopen` | OPERATOR | human undo: reopens the last episode, state→OPEN, audit. Distinct from automatic REGRESSED |

"Retry all in this incident" = the **existing** `POST /api/bulk/error-class` (RESPONDER,
full bulk rails) invoked with the incident's signature/algoVersion. The incident detail
also lists recent bulk jobs whose error-class scope matches the signature (read-only join
on the bulk-job store) so remediation outcomes are visible in context.

## 7. Ack relationship (R-BAU-01) — complementary, not overlapping

Ack = hide known noise from Stage 0 (per signature×engine×defKey, auto-resurface past
baseline, un-ack deletes). Ledger = remember/time/lifecycle (fleet-wide, never deleted).
Interactions: incident detail shows ack state read-only; resolve offers the opt-in
"also acknowledge" convenience (§6). Nothing else couples them.

## 8. Frontend

- New lazy route `/incidents` + Shell topbar link (VIEWER-visible).
- List sections: REGRESSED (alarm styling, first) → OPEN (active) → QUIET → RESOLVED
  (collapsed) — current algo generation by default, "archived generations" toggle.
- Card: state chip, exception class + normalized message, first/last seen, live total
  (lower-bound badge when truncated, R-SEM-12), engines/definitions summary, arrival
  sparkline (occurrence series; **truncated points rendered visually distinct** — a
  truncated sample is a floor, not a dip).
- Detail: timeline chart, per-engine×definition breakdown, sample raw message, episode
  list with per-episode duration (MTTR), lifecycle strip, related bulk jobs; actions:
  Resolve (with the opt-in ack checkbox) / Reopen (OPERATOR), Retry-all (RESPONDER, existing
  modal), deep-link to prefiltered `/search`.
- TanStack Query hooks per convention; generated types via `npm run gen:api`; no
  hand-written DTOs or fetch wrappers.

## 9. Non-functional & doctrine compliance

- **Do-no-harm:** zero new engine calls; DB writes bounded by live-groups-per-cycle.
- **Honesty:** truncation flags end-to-end (ledger, occurrence, UI badges + sparkline
  rendering); no fabricated zeros; quiet derived, never stored.
- **Stage-0 rule** (count-only/size=1 + dedicated DLQ scan) untouched.
- **Retention:** occurrence partitions drop at 400d (revFADP posture); `incident`/
  `incident_episode` rows persist (tiny, bounded by distinct failure classes; a
  compliance-purge path can reuse the registry-delete doctrine if ever demanded).
- **Concurrency:** single-instance BFF today, but correctness does not assume it —
  optimistic locking + state-conditional UPDATEs + idempotent upserts.
- **Secrets/PII:** messages already sanitized by R-SEM-03 (literals → `#`);
  `sample_raw_message` is the same string Stage 0 renders; DATA-CLASSIFICATION rows added.
- **Tests:** rung-1 pure state machine (OPEN/REGRESSED/zero-state gate, episode lifecycle,
  bucketing, algo-bump orphaning); rung-3 @SpringBootTest (RBAC floors, scope projection,
  audit rows, optimistic-lock conflict behavior); local-only dockerized-engine IT arc:
  seeded failing process → incident OPEN → retry-all → drain → zero-state observed →
  resolve → re-seed → REGRESSED + new episode. Awaitility with bounds; no `Thread.sleep`.

## 10. Slice plan (each = one PR: worktree → local CI → PR → review → green CI on SHA → merge)

- **S0 — design docs** (this doc + SPECIFICATION/ARCHITECTURE/IMPLEMENTATION-PLAN/
  REQUIREMENTS-REGISTER/TRACEABILITY/DATA-CLASSIFICATION deltas; R-BAU-10 minted).
- **S1 — ledger substrate (backend-only, no REST):** V18 (3 tables), `io.inspector.incident`
  package (`IncidentLedgerService` state machine, entities/repos, native upserts),
  `AggregationSample` seam + `AggregationSampledEvent`, partition maintenance extension,
  config `inspector.incidents.{enabled,quiet-window,regression-min-count}`. No OpenAPI drift.
- **S2 — read API:** `GET /api/incidents` + `GET /api/incidents/{id}` (episodes, series,
  live join, scope projection); `gen:api` regen.
- **S3 — lifecycle verbs:** resolve (incl. `alsoAcknowledge`) / reopen + audit events +
  rung-3 coverage.
- **S4 — frontend:** `/incidents` list + detail + sparklines + actions; browser-verified.
- **S5 — hardening & close-out:** dockerized-engine IT arc, related-bulk-jobs join, edge
  cases, docs close-out, TRACEABILITY row.

Model/effort: S1 carries the design risk (state machine + seam) — strongest model; S2/S3
mid+; S4 UI conventions well-established — mid; S5 verification-heavy — strong.

## 11. Deferred follow-ups (panel asks, deliberately out of v1)

Assignee/ownership + severity scoring; opt-in auto-resolve policies (quiet N days, dry-run
mode); external alert/deploy/ticket-system correlation beyond the free-text `ticket_id`;
MTTR reporting dashboard/CSV export (episodes make it derivable on demand); per-engine
episode splits; DB-side JSONB scope filtering (only if cardinality grows). Each becomes a
GitHub issue only when demand shows (R-GOV-08 doctrine).
