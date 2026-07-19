# Tutorial 06 — Bulk with guardrails: filter scope, the partial-result gate, the destructive tier

**Sign in as:** `operator` (password `dev`) at <https://pi.naumann.cloud> for steps 1–5;
steps 6–7 need `admin`.

**You will learn:** the difference between the four bulk doors, which honesty gates block a
submission and why, and how far the destructive tier makes you walk before it lets you act.

## Steps

1. **Grid-selection bulk.** Search Status = "Failed (dead-letter)" across both engines.
   Tick a handful of rows — the **bulk bar** appears ("N selected") offering the
   **intersection** of actions valid for *every* selected row (one COMPLETED row in the
   selection would remove every mutating verb). If any selected instance is 🔒 protected,
   the bar tells you it is excluded up front. Pick **Retry job**.
2. **Read the submit modal.** Scope enumeration first ("show all N composite IDs" expands
   the list), then the reason field — *required, 10+ characters, saved to the audit trail
   on every item* — the modal also **discloses the cap in its copy**: this door takes up
   to 200 items, and it names the filter-scope door (cap 5,000) as the path for larger
   sets. Nothing about caps is a surprise refusal after the fact. Submit with a reason;
   watch the per-item report in the Operations drawer (as in tutorial 02).
3. **The partial-result gate.** If your search ran while an engine was unreachable (amber
   banner), the same modal adds a blocking acknowledgment: *"This selection comes from a
   PARTIAL result set:"*, the excluded engine named, and the checkbox **"Proceed anyway — I
   understand instances outside this result set are NOT included."** Until ticked, the
   confirm is disabled with the reason. On the demo both engines are normally up, so you
   will usually not see this — but this is the gate that stops "I bulk-retried everything"
   from silently meaning "everything except billing-prod".
4. **Filter-scope bulk** ("select all matching"). Run a *filtered* search with explicit
   status chips (e.g. Failed + definition key `demoFailingRetry`), select all visible rows —
   the bulk bar now offers **"Select all ~N matching filter…"**. Read the `~N` honestly: it
   is the engine-reported uncapped match total, which can differ from the rows your grid
   fetched — two different measurements, labeled as two. The modal restates the
   *criteria*, not an ID list: at execution the BFF re-runs your search server-side, paged
   to exhaustion, and acts on what it finds *then*. The snapshot count is context, not a
   contract — which is also why prod confirmation types the **definition key**, never the
   raceable count. COMPLETED may not be in the status chips (nothing actionable is done to
   completed instances), and a truncated failure-lane scan **refuses** rather than acting
   on a silent subset. Submit or cancel as you like.
5. **Circuit-pause behavior — know it before you meet it.** If an engine's breaker opens
   mid-job, the current item waits bounded (~20 s) and retries once — safe, because a
   breaker fast-fail is guaranteed to never have reached the engine. Past the bound,
   dispatch to that engine pauses: held items settle `not_run` (never burned as failures)
   and the job ends **INTERRUPTED** with a "Continue as new job (N not run / failed)"
   offer. Other engines keep running throughout. Nothing ever resumes automatically.
6. **The destructive tier — walk up to the door** (sign in as `admin`; ADMIN is
   hard-gated at the entry). With a filtered Failed search on screen, the bulk area offers
   **"Destructive bulk…"**. Open the wizard: *"Destructive bulk — terminate every instance
   matching the current filter"*, wearing its **IRREVERSIBLE** badge.
   - First gate, **refuse-unscoped**: status + engine alone is not a narrowing filter — a
     definition key, business key, error class, activity, variable, or time window is
     mandatory. Try it with only a status chip and the refusal names the rule.
   - Narrow (add a definition key) and run the **preview**: a read-only scope enumeration
     with per-engine split. The submit never trusts it — everything, including the
     resolution itself, re-runs server-fresh at submit.
   - Reason ≥10 is required; **on prod you would type the resolved instance count** — and
     if the fleet moved between preview and submit, a count mismatch is refused
     ("Refresh the scope"), never silently re-scoped.
   - **Cancel here.** The demo is shared; there is no need to actually terminate the
     seeded instances to have learned the door. (If you do submit, each victim's
     call-activity children and audit rows will tell the story — terminate cascades are
     enumerated per item.)
7. **Check the drawer one last time.** Every job you started — including cancelled and
   interrupted ones — is still there after a full browser refresh: persisted server-side,
   with its scope label ("what was targeted") and per-item outcomes. The drawer is the
   record; toasts are just the doorbell.

## What you learned

- Four doors, one machinery: selection (200), error class (coordinates, 200), filter scope
  (criteria re-resolved server-side, 5,000), destructive (ADMIN wizard). Caps are disclosed
  in the modal copy before the server ever has to refuse.
- The gates are honesty devices: partial-result acknowledgment (never silently act on a
  subset), truncation refusal on "all matching", typed tokens that attest stable facts
  (definition key) or freshly-verified ones (the resolved count), refuse-unscoped for
  destruction.
- INTERRUPTED + "continue as new job" is the only resume there is — explicit, fresh, and
  re-audited; `not_run` and `unknown` never masquerade as failures or successes.

**Next:** [07 — Admin onboarding](07-admin-onboarding.md).
