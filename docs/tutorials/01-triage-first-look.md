# Tutorial 01 — Triage first look: read Stage 0 without touching anything

**Sign in as:** `viewer` (password `dev`) at <https://pi.naumann.cloud> — or your local
stack at `http://localhost:5173`. Everything here is read-only; a VIEWER can do all of it.

**You will learn:** how the triage landing answers "what is broken, how much, where" — and
how to read its numbers honestly (lower bounds, "as of" stamps, jobs vs. instances).

## Steps

1. **Sign in.** You land on the triage page (`/`). Note the identity strip top right:
   "Signed in as **viewer** (VIEWER)".
2. **Read the engine health strip** (header). Expect two engines, `Engine A (demo)` and
   `Engine B (demo)`, each with a DEV environment badge, a version, and four job-lane
   counts: `exec / timer / susp / DLQ`, unit-labeled **jobs**. A non-zero DLQ count is
   expected — the demo seeds deliberately-failing processes. If an engine shows
   "⚠ UNREACHABLE", that is a labeled degradation, not a crash: every count below now
   carries lower-bound honesty.
3. **Find the "as of" stamp** next to the Refresh button. Counts are served from a ~20 s
   BFF cache so ten people opening the dashboard during a P1 cost one round of engine
   queries, not ten. Click **Refresh** once — the stamp resets. (It is rate-limited;
   hammering it gets you a polite refusal, not fresher data.)
4. **Read "Failures by error class".** Each card is one normalized exception signature —
   one root cause — with counts per engine and per definition version. Expect cards for the
   seeded failures (e.g. an `ArithmeticException` from `demoFailingPayment` — "/ by zero").
   Observe on a card:
   - the count says **instances**, while the strip's DLQ said **jobs** — the two families
     are never directly comparable, which is why every number names its unit;
   - the lanes split **DLQ** (retries exhausted — needs a human) from **retrying**
     (the engine will try again by itself);
   - "sample:" shows the normalized message with literals replaced by `#`.
5. **Check the status tiles** ("Instance counts by status"). FAILED and RETRYING are
   *derived* — Flowable has no such instance states; the BFF synthesizes them from the job
   queues. The tiles are **flags, not a partition**: a FAILED instance is still inside the
   ACTIVE total. If a tile shows `≥`, a failure-lane scan hit its cap — the number is a
   floor, not a fact.
6. **Skim the leak views.** Grouped per definition: instances active > 30/90 days and
   suspended instances started > 7 days ago. Note the caption: age is measured **from start
   time** — Flowable records no suspension timestamp, so the tool refuses to pretend it
   knows time-since-suspension (a label that says exactly what it measures beats a nicer
   label that lies).
7. **Drill a group into search.** On the biggest error-class card, click the **group
   total** (title: "Open every FAILED + RETRYING instance of this one error class in the
   grid"). You land on `/search` pre-filtered by the signature; the compiled-criteria chips
   restate the scope, and the grid shows the class members. Notice the URL — it now encodes
   the entire search. Copy it, open a private window, sign in, paste: same result set.
   That URL is the incident handover primitive.
8. **Go back** (browser back — the landing is a route like any other) and click a single
   **per-version count** on the same card instead. The grid now shows only that
   definition-version slice — drill-through is scope-explicit; you always know which scope
   you carried.
9. **Try the omnibox.** Copy any Process ID from the grid, then paste it into the header
   omnibox ("paste an instance / task / job ID or a business key…"). One match navigates
   straight to the instance detail; the resolve banner tells you it checked "N of M
   engines" — a not-found is only *confirmed* when every engine answered.

## What you learned

- Stage 0 answers the triage question in zero keystrokes, from a ~20 s cache with an
  honest "as of" stamp.
- Every number carries its unit (jobs vs. instances) and its honesty marker (`≥` lower
  bounds under truncation, labeled partial coverage when an engine is out). Trust the
  badge, not your optimism.
- FAILED/RETRYING are derived flags over the job queues — the lane is the diagnosis, and
  "Explain this status" can prove any chip's derivation on demand.
- Drill-through always states its scope, and the resulting URL *is* the search — shareable,
  replayable, and the basis for saved views (tutorial 04).

**Next:** [02 — Fix a failure class](02-fix-a-failure-class.md) picks up exactly here, as
`responder`.
