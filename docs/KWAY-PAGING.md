# 🧭 K-WAY-MERGE DEEP PAGING — design + panel (v2, demand-gated)

**Status:** ★ **FEATURE COMPLETE 2026-07-09 — S0–S5 all built + merged to main, each CI-green.**
Design locked; **S0 P0 spike discharged** (live 6.3/6.8/7.1 — §6.1) → MIXED-first slices built,
**capability-gated 6.8+** (6.3.1 failed offset stability). S1 deterministic total order (R-SEM-23,
standalone) · S2 backend cursor + bounded k-way merge (R-SEM-22/R-NFR-08, `DEEP_PAGE` lane) · S3 API
surface · S4 frontend "Load more" (`useInfiniteQuery` + `aggregate()` entry cursor) · S5 live-engine
ITs (6.8 + 7.1, config-lowered caps). Authoritative source-of-truth
for the deep-paging feature (v2 demand-driven item #3). The WHAT/WHY/HOW/WHEN below drive the
deltas into `SPECIFICATION.md` (§4/§8 + deferred list), `ARCHITECTURE.md` (§2.3/§2.4),
`IMPLEMENTATION-PLAN.md` (v2 block) and `REQUIREMENTS-REGISTER.md` (R-SEM-22, R-SEM-23, R-NFR-08
added). Mirrors the doc-per-feature convention of `INSTANCE-MIGRATION.md` / `REGISTRY-CRUD.md`.

**Method:** a draft (uniform offset cursor) was put before a **5-seat panel** — Flowable-REST
wire-shape/honesty, security-architect/do-no-harm, operator-UX/product, test-manager, architect —
plus a **Gemini adversarial 6th seat**. The panel **substantially reshaped** the draft; the
"P0 RE-LOCK DECISIONS" below govern any build. Two of the draft's load-bearing wire facts are
**unproven** and gate the build behind a mandatory live spike (§6), exactly as the Instance
Migration P0 spike gated that feature (and invalidated its marquee premise).

**Discharges / reserves:** ARCH §2.4's parked sentence ("v2 can add k-way-merge cursors if real
usage demands deep paging"); adds R-SEM-22 (cursor contract), R-SEM-23 (deterministic total
order — a **standalone bug fix that ships first, regardless of whether deep paging ever builds**),
R-NFR-08 (deep-paging envelope).

---

## 1. The problem & the benefit

Today `/api/search` fans out to each engine, pulls **one bounded page per engine**
(`size = min(maxPageSize=200, requested)`), merges + sorts the whole set in the BFF
(`SearchService.aggregate`), and returns it single-shot; the frontend hands the entire merged
array to AG Grid's client-side row model. There is no cursor, no "next page", no infinite scroll.
When an engine holds more than its page, the honest fallback is the `perEngine.total > fetched`
badge ("138 of 2,410 — narrow your filter") and, on the bulk path, a hard refuse above the cap.

The benefit of the feature is **cursor-based browsing through the globally-sorted merged stream
across all engines** — scroll past the first ~200-per-engine of a time-ordered result set,
in correct sort order spanning N engines, without pulling everything into memory and without
breaking sort correctness or the per-engine do-no-harm bounds.

**Why this is v2 and demand-gated (unanimous panel view):** the tool's entire triage doctrine is
*narrow your filter* — refuse-unscoped is a feature, not an apology (ARCH §2.4, SPEC §7 destructive
bulk). Deep paging is the one affordance that sells *scroll* instead of *narrow*, so it must earn
its place. The **one honest use-case** where paging beats narrowing: a **live wide incident** where
the operator wants the globally time-ordered merged feed ("newest first, all engines") and *cannot*
narrow further because the discriminator (error signature, definition) is exactly what they are
still discovering. Everything else (omnibox resolve, Stage-0 error-class drill) already beats
scrolling. **Build trigger (ARCH §2.4 gate):** instrument how often operators hit
`perEngine.total > fetched` on a *time-sorted* search and then do **not** narrow. Empty signal ⇒
do not build; the "narrow your filter" banner stays the whole feature.

---

## 2. The wire wall — why a naïve cursor doesn't work

Any deep-paging design must survive the Flowable REST wire-shape (REST-only iron rule):

- **No cursor/keyset anywhere in Flowable REST.** Every collection is classic offset paging
  (`start` + `size`). Deep paging past page 1 is **O(offset)** on the engine DB (skip-and-discard).
- **`failureTime` is not a queryable or sortable field.** It is BFF-*derived* from job `createTime`;
  jobs reject `createTime` as a sort field (400 — sortable job fields are
  `id, dueDate, executionId, processInstanceId, retries, tenantId`). **Keyset on failureTime is
  impossible over REST.**
- **The two plan shapes page completely differently** (the finding that reshaped the draft — §3 F1):
  - **MIXED** (historic-first, default `startTime desc`): the hydration loop already threads a
    per-engine `start` offset → an engine offset is a coherent resume position.
  - **INVERTED** (FAILED/RETRYING, `failureTime desc`): scans the DLQ + exception lanes to a cap
    **unsorted**, rolls children→roots, dedups into an **unordered set**, hydrates, then sorts by
    the **BFF-derived** `failureTime`. **There is no engine-side `start` to advance** — a per-engine
    startTime offset does not track global failureTime order, and each page would **re-scan
    O(dlqScanCap)**. A uniform offset cursor across both plans is therefore **unsound**.
- **`startTime` keyset is *maybe* viable — but on an unproven premise.** The draft claimed
  whole-second date granularity makes keyset unsound; that fact was imported from the **GET job
  lanes** and is **untested for the historic-query POST body** (`startedBefore`/`startedAfter`).
  This is exactly the cross-surface assumption the Instance Migration spike existed to kill →
  **spike-gated (§6)**.

---

## 3. Panel discussion (6 seats → synthesis)

### Flowable-REST / honesty seat (the load-bearing wire seat)
- **INVERTED offset is incoherent** (above) — the draft's headline use case ("all FAILED, newest
  first") is the one it can't page with a uniform offset. Split the cursor by plan; **MIXED-first**.
- The "whole-second kills keyset" claim is an **overclaim** — imported from GET job params, unproven
  on the historic POST body; a bounded same-second overlap keyset is plausibly viable and *cheaper*
  than O(offset). Don't reject keyset on an unproven premise; let the spike decide.
- **Offset is not stable per-engine** either: the repo's own `listProcessDefinitionVersionsDesc`
  comment documents Flowable's default single-field sort as dup/skip-prone under concurrent writes,
  and a BFF `compositeId` tiebreak can only order rows *received in the same page* — it **cannot
  repair an engine-side page-boundary shuffle**. The DLQ scan has *no* sort at all.
- **Verdict: P0 spike MANDATORY.** Same class as Instance Migration.

### Security-architect / do-no-harm seat
- **`filterHash` gives ZERO integrity against a crafted cursor** (attacker controls both the filter
  and its hash). The `offsets` map is attacker-controlled input fed straight into `start=` across
  ≤10 engines. **The real DoS ceiling is a server-side *inbound* bound-check** — reject (400) any
  cursor whose per-engine offset (or their sum) exceeds the depth cap, and hard-clamp per-engine
  `size` to `maxPageSize`, **before** the fan-out. **HMAC does NOT solve DoS** (a legit signed
  cursor still reaches the cap by honest scrolling); it is optional defence-in-depth only.
- **Deep paging must NOT share the INTERACTIVE 8-permit bulkhead.** An offset-9,999 query is far
  heavier than offset-0; a scroller (or attacker firing cursors at `offset = cap-1`) starves
  page-1 search. **Add a dedicated `CallPriority.DEEP_PAGE` lane** (mirror the M4 `BACKGROUND`
  sampler lane — distinct instance name so it can't reuse the 8-permit bulkhead).
- Cap is **per-engine**, not a single global (offset cost is per-engine → global-10k = 10× intended
  load across 10 engines). **Add a cursor TTL** (`issuedAt`, minutes) → 400 "cursor expired" to
  bound replay + incoherent-merge. Exclude deep pages from the R-NFR-02 P95 budget.
- The filter always lives in the **request body**, never in the cursor — badges recompute
  server-side; the cursor's `filterHash` can only *fail* the match, never substitute a filter.
  Keep it that way. **Verdict: BLOCK-as-written → accept once the inbound cap-check + DEEP_PAGE lane
  land in the design.**

### Operator-UX / product seat
- **Do not swap the grid row model.** Reject **numbered pages** (dishonest over lower-bound totals)
  and **infinite scroll** (counts shift under the reading thumb, violating R-UXQ-06; a select-all
  footgun; implies a bottomless feed). Adopt **"Load more" + `useInfiniteQuery`**, surfaced
  **only** on overflow (`perEngine.total > fetched`) — ~95% of searches never touch the cursor
  machinery. Each click is one bounded fan-out; the honesty badge updates only at a user-triggered
  moment, never under the scroll.
- **Two-door selection is preserved and a dangerous third door is forbidden:** "select all matching"
  → the **filter path** (criteria-only, server re-resolved, 5k cap); ticked rows → the **200-cap
  selection** path. The "select all *loaded* rows as an ID list" middle door must be **unreachable**
  (it is the footgun *and* would need AG Grid Enterprise → R-GOV-05 violation).
- **The depth wall is a filter seam, not a dead-end nudge.** At the cap, hand back the last-shown
  sort key as a pre-filled time bound ("failed before 14:03:22 — apply to continue") — the honest
  bridge between paging and narrowing, satisfying R-UXQ-05 ([what]+[why]+[next]).
- **Snapshot honesty:** the deep-paged set is already under the lower-bound amber; add one calm
  seam line ("loaded more as of 14:36 — newer failures won't appear until Refresh") and a
  `pagingCoherence` flag; **Refresh resets the cursor chain and drops any deep selection.**
- **Verdict: gate to time-ordered sort modes; build only on measured demand.**

### Test-manager seat
- **Extract a pure merge/cursor seam** out of `SearchService.aggregate()` into `StatusJoin` /
  a `PagingCursor` class (collection-in/collection-out) — it is the rung-1 home for R-SEM-22/23.
  Today the sort is inline with **no** merge tests.
- **The caps MUST be config-lowerable.** Prove multi-page k-way merge at `max-page-size:2,
  deep-paging-max-depth:6` over a doubled engine registration (the existing `engine-tiny`
  precedent) on ~10 seeded instances — **never** seed thousands (TEST-STRATEGY §10).
- **Page-1 regression:** the R-SEM-23 total-order fix (String→Instant + compositeId tiebreak)
  changes existing output → red-first goldens required (the current IT only asserts monotonicity).
- **Concurrent dup/skip bound is a rung-1 property** (inject an insert/delete straddling the
  boundary), *not* a rung-4 race — a live engine can't deterministically reproduce it. Keep the
  honest wording: "bounded and labeled", never "stable".
- **Crafted-cursor test mandatory:** forged `offsets:{engine-a:9_999_999}` must be refused by the
  cap **before** fan-out — assert via MockWebServer that **zero** out-of-range engine requests fire
  (this test *decides* whether bare filterHash suffices or HMAC is needed).

### Architect seat
- **Cursor is a tagged union**: `HISTORIC{offsets, boundaryKey, boundaryIds}` (build first) and
  `INVERTED{...}` (later, if demand proves it) — do **not** paper over F1.
- **Do not reuse the exhaust machinery as the paging engine** (it harvests-all and *refuses*
  over-cap — the opposite of paging). Reuse two extracted sub-parts: a `PageWindow(start,size)` seam
  threaded into `searchOneEngine` (replacing the `exhaustCap` flag), and the extracted+hardened
  comparator.
- **Stateless in-token cursor: YES, decisively** (stateless Spring chain; Traefik multi-replica
  demo — a server-side store would need sticky sessions/shared state). Two gaps: mandatory
  server-side offset re-validation (see security seat) and a **bounded `boundaryIds`** (≤ window)
  with a documented fallback when same-key ties exceed it.
- **The 10,000 cap is a guess** — set it by measurement, or align to the existing 5,000 bulk cap
  for one operator mental model; state a **separate looser latency class** for deep pages.

### Gemini (adversarial 6th seat)
- **Single most fatal flaw:** the INVERTED plan's incoherent offset (startTime-ordered hydration
  re-sorted by failureTime) → "cannot guarantee correct or complete results" for a triage tool.
  Ranked next: un-HMAC'd cursor = trivial fleet-wide DoS; O(offset) cost curve kills the 10k cap
  vs P95≤3s; deep paging is largely YAGNI for a "narrow" tool.
- **Reshape recommendation:** abolish the *uniform* cursor; make a **time-window** the primary
  interactive filter with a hard total-result cap; offer **background CSV export** (on the thin
  lane) for genuinely large sets; any surviving "load more" cursor **must** be bound-checked (and,
  it argued, signed). — *Adopted in part:* the time-window bound becomes the **depth-wall filter
  seam** (UX seat) and export-for-bulk stays the existing bulk path; a full time-window-mandatory
  redesign was **not** adopted (it would regress the existing single-shot search UX), but its DoS
  and cost-curve findings are load-bearing in the RE-LOCK below.

---

## 4. RE-LOCK DECISIONS (govern any build; supersede the draft)

1. **MIXED / time-ordered first; cursor is a tagged union.** Ship deep paging for the
   `startTime desc` (MIXED) mode. Cursor = `HISTORIC{v, issuedAt, filterHash, sortBy, order,
   offsets{engineId:rowsConsumed}, boundaryKey, boundaryIds[]}`. The **INVERTED / `failureTime`**
   mode is initially **capability-gated OFF for deep paging** — the overflow banner offers the
   depth-wall filter seam instead. A bounded BFF-materialized `INVERTED` cursor variant is a
   *later* slice, only if demand proves it.
2. **Deterministic total order (R-SEM-23) is a standalone MUST, lands first.** Extract the
   comparator into `StatusJoin`; add `compositeId` as the final tiebreak on **all** sort modes;
   compare `startTime` as a parsed `Instant`, not a raw String. This fixes a *current* page-1
   nondeterminism/mis-order bug and ships even if deep paging never builds. Honesty preserved: the
   BFF tiebreak bounds duplicate *emission* but cannot repair engine-side boundary *skips* under
   concurrent mutation → labeled, never claimed stable.
3. **Do-no-harm rails (all mandatory in R-NFR-08):**
   - **Inbound cursor bound-check + size clamp before fan-out** — reject (400) any per-engine
     offset (or their sum) over the cap; clamp `size ≤ maxPageSize`. This — not HMAC — is the DoS
     ceiling.
   - **Dedicated `CallPriority.DEEP_PAGE` bulkhead + breaker lane** (distinct instance name),
     so deep scroll degrades itself, never page-1 search.
   - **Per-engine depth cap**, config-lowerable, default set by the S0 measurement (candidate:
     align to the 5,000 bulk cap). `depthCapped` is per-engine.
   - **Cursor TTL** (`issuedAt`) → 400 "cursor expired, start over".
   - **Bounded `boundaryIds`** (≤ window) with a documented fallback.
   - Deep pages are a **separate latency class**, excluded from the R-NFR-02 P95 gate.
   - HMAC is optional defence-in-depth, **not** required.
4. **UX = progressive disclosure, not a row-model swap.** "Load more" + `useInfiniteQuery`,
   surfaced only on overflow, time-ordered modes only. Two-door selection preserved (no
   loaded-rows-as-ID-list door). Depth wall = pre-filled time-bound filter seam. Snapshot honesty
   via the existing lower-bound amber + one calm seam line + a `pagingCoherence` flag; Refresh
   resets the chain and drops deep selections.
5. **Stateless in-token cursor**, opaque `base64url(JSON)`, idempotent/read-only; the authoritative
   filter is always the request body, never the cursor.
6. **P0 spike (§6) MANDATORY before build — DISCHARGED 2026-07-09 (§6.1).** Verdicts: build
   unblocked MIXED-first; **capability-gated 6.8+** (6.3.1 fails offset stability); cap = 5000/engine;
   R-SEM-23 Instant-parse confirmed load-bearing; INVERTED door known-open on 6.8/7.1 (still deferred).

---

## 5. Surface deltas (when built)

- `SearchRequest`: add optional `cursor: string`. When present, `pageSize` is the **global** rows
  to emit; per-engine `size` is fixed at the window internally.
- `SearchResponse`: add `nextCursor: string|null`, `depthCapped: boolean` (per-engine), and
  `pagingCoherence` (`snapshot` when deep-paged). Existing `perEngine`/`statusCounts`/truncation
  markers unchanged; deep-page markers ride the **same** `perEngine` envelope.
- Frontend: "Load more" via `useInfiniteQuery`, `getRowId` = `compositeId`; reuse the
  partial-results banner; depth-wall filter-seam CTA.

---

## 6. P0 spike (S0) — mandatory, must run live on 6.3.1 / 6.8.0 / 7.1.0

Same method as the migration spike (curl **and** extracted-bytecode cross-check). Must prove:
1. **Historic-body date granularity** — does `POST /query/historic-process-instances` with
   `startedBefore`/`startedAfter` accept sub-second precision, or truncate/400 like the GET job
   params? (Decides whether a startTime keyset is even constructible.)
2. **startTime echo precision + format** — sub-second digits present? offset-form vs `Z`?
   (A keyset boundary can't be reconstructed without it.)
3. **Offset stability** of `sort=startTime&order=desc` across two `start` windows straddling a
   same-second tie cluster, **with a concurrent insert in flight** — measure real dup/skip; confirm
   no accepted secondary sort field exists.
4. **DLQ default-order stability** across paged `start` windows, and whether `sort=id&order=asc`
   is honored on the deadletter lane (the only candidate for a stable resumable DLQ offset).
5. **failureTime unsortable** — confirm the job-sort 400 live (closes the INVERTED-offset illusion).
6. **6.3 paging-param cliff** — does 6.3 silently drop paging params (cf. the businessKeyLike
   cliff)? If so, deep paging is capability-gated **6.8+**.
7. **Cost model** — measure offset-near-cap query cost per engine to *set the real depth cap*.

**Artifacts to leave behind:** `docker/spike-kway-paging.sh` (capture script), a findings section
here with per-version curl evidence, and **captured page-boundary corpora promoted to rung-1
fixtures**. If the spike shows offset instability on any version, R-SEM-22 is dead on that engine
and the panel **re-locks before build** — exactly as Instance Migration did.

### 6.1 FINDINGS — S0 ran live 2026-07-09 (`docker/spike-kway-paging.sh`)

Ran against the dockerized matrix (`docker-compose.dev.yml`): **6.8.0** :8081, **7.1.0** :8083,
**6.3.1** :8084. Per-version evidence below. **Net: the spike CONFIRMS the MIXED-first offset build
on 6.8/7.1, forces a 6.8+ capability gate (6.3.1 fails offset stability), and CORRECTS two draft
wire-facts** — neither correction changes the RE-LOCK, but both are now known instead of assumed.

| # | Probe | 6.8.0 | 7.1.0 | 6.3.1 | Verdict |
|---|---|---|---|---|---|
| 1 | POST `/query/historic-process-instances` sub-second `startedBefore` | 200 | 200 | 200 | Accepted everywhere — a startTime **keyset is constructible** in principle (not used; build is offset). |
| 2 | historic `startTime` echo | `…Z` +ms | `…Z` +ms | `…Z` +ms | Millisecond `Z`-form on all. |
| 2b | deadletter `createTime` echo (the `failureTime` source) | **`…+00:00`** +ms | **`…Z`** +ms | **null/absent** | **Cross-engine form split is REAL → R-SEM-23 `Instant`-parse is load-bearing** (a `String` compare mis-orders `+00:00` vs `Z`). |
| 3 | offset stability, `sort=startTime desc`, two identical scans | **stable** | **stable** | **UNSTABLE** | 6.3.1 reproducibly swaps adjacent same-second rows (pos 50/51) between identical scans → a page boundary inside a tie-cluster dups/skips. |
| 3b | same-second clusters per top-100 (max cluster) | 21 (11) | 19 (9) | 29 (8) | Ubiquitous — a secondary tiebreak is **essential**, not cosmetic. |
| 4 | deadletter `sort=id&order=asc` | 200 | 200 | 200 | Honored everywhere (candidate stable DLQ offset; INVERTED gated off anyway). |
| 5 | deadletter `sort=createTime` (i.e. `failureTime`) | **200, genuinely orders** (ASC≠DESC, correct direction) | **200, genuinely orders** | **400 rejected** | **Draft's "failureTime unsortable (400)" is FALSE on 6.8/7.1** — imported from the GET job-list params, untrue on the deadletter lane. Only 6.3.1 rejects. |
| 6 | 6.3 paging-param cliff (`start`/`size`) | pages | pages | **pages (no cliff)** | The businessKeyLike-class cliff does **not** apply to historic paging — 6.3.1 pages fine; it's *offset stability* (row 3), not paging support, that fails on 6.3.1. |
| 7 | cost `start=0` vs `50` vs `100` (size=10) | ~4 ms flat | ~5–6 ms flat | ~6 ms flat | Flat at the dev corpus scale (100s of rows). **O(offset) is NOT measurable at TEST-STRATEGY §10-safe scale** (never seed thousands) → the cap is set by *reasoning*, not this number. |

**Decisions locked by S0 (feed the build slices):**

1. **Deep paging is capability-gated `6.8+`.** 6.3.1 fails offset stability (row 3) — this is exactly
   the design's own kill-switch ("offset instability on any version ⇒ R-SEM-22 dead on that engine").
   6.3.1 (and any engine that fails the stability probe) gets the **depth-wall filter seam**, never the
   cursor. This matches the repo's existing 6.8+ gating precedent (CMMN, external-worker).
2. **`deep-paging-max-depth` = 5000 per-engine** (config-lowerable), aligned to the R-NFR-01 v1.x
   query-bulk cap for one operator mental model. Set by reasoning + the conservative side of the
   unmeasurable cost curve, **not** by a measured knee — documented as such, revisited if a real
   large corpus ever lets us measure the O(offset) knee.
3. **R-SEM-23 (`startTime` as `Instant`, `compositeId` tiebreak) is confirmed load-bearing**, not
   speculative: row 2b proves engines emit different ISO forms for the same instant, and row 3b proves
   same-second ties are everywhere. Ships first (S1), stands alone.
4. **INVERTED is still gated off for THIS build — but the door is now known-open on 6.8/7.1.** Row 5
   corrects the draft: `failureTime` (job `createTime`) **is** an engine-honored sort on the deadletter
   lane for 6.8/7.1, so a future bounded INVERTED cursor slice is wire-viable there (it was assumed
   impossible). Deferred, not dead. MIXED-first stands.
5. **Concurrent-mutation dup/skip stays "bounded + labeled, never stable"** even on 6.8/7.1: row 3 is
   stable only with no writes in flight; the R-SEM-23 tiebreak bounds duplicate *emission* at the merge
   but cannot repair an engine-side page-boundary skip under a straddling insert (a rung-1 property, not
   a live race — S1/S2 tests).

---

## 7. Slice plan (each CI-green + independently mergeable)

- **S0 — P0 wire-shape spike** (§6). ✔ **DISCHARGED 2026-07-09** (§6.1) — de-risked the mechanism,
  gated the feature 6.8+ (6.3.1 offset-unstable), set the cap at 5000/engine by reasoning.
- **S1 — Deterministic total order (R-SEM-23), standalone.** ✔ **LANDED 2026-07-09.** Comparator
  extracted into `StatusJoin.resultOrder(sortBy)`; `compositeId` final tiebreak; `startTime` as
  parsed `Instant`. Rung-1 goldens in `StatusJoinTest.ResultOrder` (tiebreak, `+00:00`/`Z` same-instant
  equality-then-tiebreak, nullsLast, failureTime-mode, shuffle-stable). No API change — ships page-1
  determinism immediately.
- **S2 — Backend cursor + bounded page merge (R-SEM-22, R-NFR-08).** ✔ **LANDED 2026-07-09.**
  `PagingCursor` (codec + `filterHash` bind + inbound **per-engine** offset/cap re-validation + TTL +
  bounded `boundaryIds`) and the pure bounded k-way `mergePage`; a `PageWindow` seam threaded into
  `searchOneEngine`/`mixedPlan` with `CallPriority` threaded through the whole MIXED enrichment chain so
  the deep-page fan-out runs on the new `CallPriority.DEEP_PAGE` bulkhead+breaker lane and never starves
  the 8-permit interactive lane; per-engine `deep-paging-max-depth` on `EngineConfig` (default 5000);
  `SearchService.deepPage` orchestration (MIXED/`startTime`-only — `failureTime`/INVERTED refused → the
  filter seam); `depthCapped`. **Offset advances over the engine's RAW result set** (not the BFF-filtered
  subset) by `|raw ≥ boundary| − |kept-but-unemitted-at-boundary|`, which drains a same-instant cluster
  larger than the window without dup/skip. Rung-1 `PagingCursorTest` (codec, bound-check, filterHash,
  multi-page + same-instant-cluster merge walks) + rung-2 `SearchServiceDeepPageTest` (crafted over-cap
  cursor refused pre-fan-out, `verifyNoInteractions`). **No API surface yet — S3 wires the controller
  and adds the web-layer RBAC test.**
- **S3 — API surface.** ✔ **LANDED 2026-07-09.** `SearchRequest.cursor` + `SearchResponse.nextCursor`/
  `depthCapped`/`pagingCoherence` (delegating convenience ctors, no call-site churn); `SearchController`
  branches on a present cursor → `deepPage` → maps `DeepPage` onto `SearchResponse` (`pagingCoherence =
  "snapshot"`), the existing `IllegalArgumentException`→400 handler covers a crafted/expired/mismatched
  cursor; `schema.d.ts` regen (the 4 new fields). Rung-3 `SearchDeepPageApiSpringTest` (cursor round-trips,
  markers serialize, garbage/over-cap/FAILED-only cursors are 400-not-500 for an authenticated reader).
- **S4 — Frontend "Load more".** ✔ **LANDED 2026-07-09.** `useInfiniteQuery` cursor chain
  (`useSearch.ts`), the flattened+compositeId-deduped feed fed to the client-side grid; overflow-only
  surfacing (the BFF emits the ENTRY `nextCursor` from `aggregate()` only when a MIXED/`startTime`
  search overflows, so `hasNextPage` gates the button); two-door selection preserved (no
  loaded-rows-as-ID-list door); depth-wall filter seam (pre-filled `startedBefore` at the last-shown
  key); snapshot seam line; Refresh resets the chain + drops the selection (keyed on the search
  identity, not per-page fetch). Playwright `e2e/deep-paging.spec.ts` (URL-predicate route mock branched
  on `cursor` — append/seam/end + criteria-only re-send). Backend: `aggregate()` mints the entry cursor
  via `mergePage`; `mixedPlan` always collects raw window keys.
- **S5 — Live-engine ITs (engine-harness).** ✔ **LANDED 2026-07-09.** `AbstractKwayPagingIT` + the
  6.8 (`KwayPagingIT`) and 7.1 (`Kway7IT`) concrete subclasses, wired into the CI integration matrix
  (`it-kway` / `it7-kway` profiles, caps config-lowered to `max-page-size:2`, `deep-paging-max-depth:6`).
  Proves on live engine state (seeded strictly over REST, unique businessKey per test): a multi-page
  deep scroll emits every seeded instance **exactly once in `startTime desc` order** (no dup/skip,
  cross-version — the 7.1 `Z`-form vs 6.8 `+00:00`-form corroborates the R-SEM-23 Instant-parse);
  paging past the lowered cap flags `depthCapped`; a crafted over-cap cursor is a **400**; a dropped
  engine mid-scroll degrades to `perEngine.ok=false` inside a 200 with the healthy rows intact.

---

## 8. Docs-lockstep (this change)

- **New `docs/KWAY-PAGING.md`** (this file) — authoritative design.
- **`ARCHITECTURE.md` §2.4** — replace the parked "v2 can add k-way-merge cursors…" sentence with
  a pointer to this design + the MIXED-vs-INVERTED distinction; **§2.3** note that the total-order
  tiebreak now applies to page-1.
- **`SPECIFICATION.md`** — promote the deferred "k-way-merge paging" list item to point at this
  doc with its build trigger.
- **`IMPLEMENTATION-PLAN.md`** — expand the deferred one-liner into a v2 sub-section in the house
  style.
- **`REQUIREMENTS-REGISTER.md`** — add R-SEM-22, R-SEM-23, R-NFR-08.
- **`TRACEABILITY-MATRIX.md`** — add rows for the three IDs (+ a perf-harness row for R-NFR-08
  mirroring the R-NFR-02 §C-10 gap).

Deeper SPEC §4/§8 *behavioral* edits land **with the build slices** (spec-sync), not now — the
behavior is unbuilt and spike-gated. R-SEM-23, being a standalone bug fix, may build ahead of the
rest.
