# Tutorial 05 — Instance surgery: fix the data, retry the job, pause the case, preview a migration

**Sign in as:** `operator` (password `dev`) at <https://pi.naumann.cloud>; step 8 switches
to `admin`.

**You will learn:** the FIND → DIAGNOSE → FIX → VERIFY arc on a single instance — the
typed variable editor with compare-and-set, the offered-never-automatic retry, tier-0
suspend/activate, and what the migration pre-check honestly is (and isn't).

## Steps

1. **Find a broken instance.** On the triage landing, open the `demoFailingPayment`
   error-class card and click a per-version count — or search Status = "Failed
   (dead-letter)", Definition key = `demoFailingPayment`. Open a row (Enter or
   double-click) to land on `/inspect/engine-…/…`.
2. **Diagnose from the vitals.** The status chip reads **FAILED — needs action**; the
   why-stuck strip gives the exception first line (`ArithmeticException: / by zero`),
   retries "3/3 exhausted", and the failing activity. Open the **Errors & Jobs** tab: one
   job in the **dead-letter lane** — retries exhausted, nothing will ever happen without
   you. Expand the stacktrace if you like. The cause is data: the instance was started with
   `divisor = 0`.
3. **Fix the variable.** Open the **Variables** tab. The ledger is typed — `divisor` shows
   a *number* chip, value `0`, process scope. Click **✎ edit**. The inline panel opens
   under the row (the ledger and vitals stay visible), restating the target; the current
   value stays on screen. Type `1` — the live echo confirms *"will be stored as integer 1"*
   (the echo is the contract: no silent string-ification). Note what you did NOT have to
   touch: a JSON payload. Click **"Review change…"**.
4. **Read the verification modal** — the one modal in the flow, and you invoked it. Top to
   bottom: the target restatement; the generated sentence ("Change **divisor** from **0**
   to **1** …"); the Current → After panes; the collapsed "exact request" expander (the
   verbatim payload + compare-and-set precondition). The confirm button *restates the
   change* — it never says just "Confirm". Reason is optional here (dev engine; required on
   prod). Confirm.

   *If someone else edited the variable meanwhile you get the three-value CAS conflict
   panel instead — your starting value, your new value, the engine's current value, with
   attribution — and the only forward paths are "Start over from the current value" or
   cancel. There is no overwrite-anyway button, by design.*

5. **Take the offered follow-on.** On success the ledger re-renders from re-fetched server
   truth, and the tool offers **"Retry the failed job?"** — the #1 incident sequence
   (fix data → retry), offered, never automatic. Accept it (or go to Errors & Jobs and use
   the inline "Retry job …?" confirm). The outcome toast states the explicit delta: the job
   moved back to the executable queue, retries reset.
6. **Verify.** Within seconds the async executor re-runs the step — this time `100 % 1`
   succeeds. Refresh the detail: status chip **COMPLETED**, dead-letter lane empty, the
   Timeline tab shows the completed path. Compare this with tutorial 02, where retrying
   *without* fixing the data boomeranged: same verb, opposite outcome, and only the
   data fix distinguished them.
7. **Suspend / activate (tier 0).** Find an ACTIVE instance (search Status = Active,
   definition `demoUserTask`) and open it. The **Suspend** button wears its `REVERSIBLE`
   badge on the button itself. On this dev engine it acts on a single click (on prod,
   irreversible-side-effect tier-0 verbs get a two-step inline confirm) — click it and read
   the toast: it names the delta *and the compensating verb* ("… suspended — reversible;
   Activate resumes it"), and claims nothing the call doesn't guarantee (a dead-letter job
   would stay dead-lettered — suspend touches executable jobs only). Click **Activate** to
   restore it. Both actions are in the instance's Audit & Notes tab with your name — no
   reason prompt at tier 0, but full attribution.
8. **Migration — see the guard before the verb.** Still as `operator`, open any active
   instance and find **Migrate** in the toolbar: greyed, tooltip naming the gate — migrate
   is tier 3, ADMIN floor. Sign back in as `admin` and reopen it: "Migrate — move this case
   to a newer process version", a target-version picker, and **"Check mapping →"**. Read
   the step note: *""Check mapping" compares the two versions' activities. Flowable does
   the real check only when you execute."* That banner is the whole point: Flowable's REST
   API exposes **no migration validator**, so the mapping check is an Inspector estimate
   (`engineValidated: false`) — the engine is ground truth only at execute.

   > **Demo limitation — full migrate needs the local stack.** The demo seeds each
   > definition at a single version, so there is no target version to migrate to. On your
   > local stack, deploy `docker/processes/demo-migration-v1.bpmn20.xml`, start an
   > instance, deploy `demo-migration-v2.bpmn20.xml` (same key, so it becomes v2), then run
   > Migrate: Check mapping → review the auto-map/flagged table → execute with a reason.
   > **On a prod engine** execute additionally demands the typed **business key** and the
   > move is IRREVERSIBLE; a post-dispatch timeout lands as UNKNOWN + Verify-now, never an
   > auto-retry.

## What you learned

- The editor edits a *value*, never a payload: typed widgets, a parsed echo, a
  verification sentence generated from the same request object it describes, and
  compare-and-set with a no-overwrite conflict path.
- Follow-ons are offered, never automatic; outcome toasts state exactly the delta the call
  guarantees, plus the compensating verb for reversible actions.
- Retry heals only what data (or downstream) fixes have already made healable — VERIFY is
  a step, not an assumption.
- The migration pre-check is honestly labeled an Inspector estimate; guard friction
  (ADMIN floor, typed business key on prod, IRREVERSIBLE badge) scales with blast radius.

**Next:** [06 — Bulk with guardrails](06-bulk-with-guardrails.md).
