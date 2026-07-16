# 🎭 USABILITY MISSIONS — tester-visible briefs

Companion to `docs/usability/GOAL-CATALOG.md` (v1.0, **evaluator-only** — never feed the
catalog to a tester). The harness extracts each mission's `TESTER BRIEF` block verbatim,
substitutes `{{PLACEHOLDER}}` values from live engine state at run time, prepends the
generic tester protocol (GOAL-CATALOG "RUN PROTOCOL → Tester protocol"), and hands it to
one naive Sonnet-tier tester signed in as the stated user (pw `dev`, app at
http://localhost:5173).

Evaluator blocks (`COVERS`, `STAGING`) are for the runner/reconciler only.

---

## M1 · "3am pager: payments failing" — wave 1 · user `responder`

COVERS: R-SEM-03 · R-SEM-02/a/b · R-SEM-04/a/c/e · R-L3-05 · R-SAFE-04 · R-SAFE-01/a ·
R-AUD-06/a/b · rubrics R-UXQ-05/06.
STAGING: standard seed; runner extracts {{FAILED_ID}} (a demoFailingPayment instance id),
{{RETRYING_ID}} (a demoFailingRetry instance id), {{GARBAGE_ID}} (a well-formed UUID that
exists nowhere).

TESTER BRIEF:

> You are the on-call support engineer. It is 3am. You have never used this tool — you
> only know it inspects the company's workflow engines. Your pager says: _"Expense/order
> back-office flows failing since last night. Ops chat mentions 'payment steps erroring
> out'. No instance IDs in the alert."_
>
> 1. Get an overview: what exactly is broken right now, how much of it is there, and
>    which deployment/version does it point at? Write down your conclusion and what on
>    screen told you.
> 2. A colleague pastes two case IDs from a log: `{{FAILED_ID}}` and `{{RETRYING_ID}}`.
>    You have capacity to escalate exactly ONE tonight. Decide which — and say why the
>    other can wait. Cite the exact on-screen wording that made the difference.
> 3. Open the case you chose. From what you first see, state in one sentence what this
>    case is doing and why it is stuck. Then dig to the underlying technical cause (the
>    actual error) and name it.
> 4. Fix it the way the tool suggests a failed step is fixed. Afterwards: how do you KNOW
>    what happened as a result? (It may fail again — what tells you either way?)
> 5. Night shift ends. Leave a warning for the next engineer on this case: "payment
>    gateway suspected, do not push it again before 7am" — wherever you believe the next
>    person will actually see it.
> 6. Prepare an escalation for the ticket system: one pasteable text that lets a
>    colleague reach this exact case and understand its state without you.
> 7. Finally: this ID also came in from a log line — `{{GARBAGE_ID}}`. Find what it
>    belongs to, or state with confidence what you know about it.

## M2 · "The stuck multi-part order" — wave 1 · user `responder`

COVERS: R-UXQ-12 · R-UXQ-11 · R-SEM-19 · R-SEM-01 · R-L3-03 · rubrics.
STAGING: standard seed + F-G1 (deployed); runner extracts {{PARENT_BK}} (demoParent
businessKey or id) and confirms the wide parent `ORD-BATCH-2107` has 60 children.

TESTER BRIEF:

> You are the on-call support engineer, first time in this tool. Ticket: _"Order
> {{PARENT_BK}} is stuck. It's one of those multi-part orders that spawn sub-work."_
>
> 1. Find that order. If the search shows several related rows, state which one is the
>    top-level order and how you can tell.
> 2. The order shows as failed — but WHERE is the actual failure? Get to the exact failed
>    step and fix it (run the failed step again). Describe the path you took.
> 3. Separate ticket, same shift: _"Batch order ORD-BATCH-2107 looks stalled."_ Open it
>    and answer: how many sub-cases does it have in total, and are they all visible to
>    you? Is the tool being honest about what it shows vs what exists? Cite the wording.
> 4. An escalation engineer asks for "the raw data behind that variables view, exactly as
>    the engine returns it" for one of these cases. Produce it.
> 5. Along the way, note every term the tool used that a newcomer wouldn't know (e.g.
>    whatever jargon appears on the failure views). For each: could you find out what it
>    means WITHOUT leaving the app? Quote what the app gave you.

## M3 · "Bad data, careful hands" — wave 2 (serialized) · user `operator`

COVERS: R-UXQ-13/a/b/c · R-SEM-09/a/b · R-NFR-06 · R-AUD-07 · R-SAFE-02 · rubrics.
STAGING: standard seed; runner extracts {{ACTIVE_ID}} (ACTIVE demoUserTask instance),
{{JSON_ID}} (an ACME instance with a structured/json variable, e.g. acmeVendorEnrichment),
and prepares the colleague-simulation command {{OOB_MUTATION_CMD}} (out-of-band REST edit
of the same variable, attributed to another dev user) and {{OOB_RESOLVE_CMD}} (retries the
job on the second sacrificial instance as another user). Sacrificial F-G10 cohort tagged
`uxrun-m3-*` for the destructive-comprehension question.

TESTER BRIEF:

> You are a workflow operator. Three data-repair jobs tonight; you know another team is
> working the same incident, so tread carefully. You have a terminal available for the
> one step below that says "run this command".
>
> 1. On case `{{ACTIVE_ID}}`: the numeric variable `amount` is wrong — it must become
>    `250`. Change it. Before you commit, state exactly what the system says it is about
>    to do, in your own words. Then commit and prove the change took effect.
> 2. Same case: the text variable `note` must be CLEARED — the business says "there must
>    be no note value at all". Do it. If the tool asks you to make any distinction while
>    clearing, explain the choice you made and why.
> 3. On case `{{JSON_ID}}`: inside the structured variable, one field needs flipping
>    (the runner will name it: {{JSON_LEAF_TASK}}). Change ONLY that field. Report
>    whether you ever had to hand-edit raw JSON text to do it.
> 4. Back on `{{ACTIVE_ID}}`: `amount` must now become `300`. Open the edit, enter the
>    new value — then STOP. Run this command in your terminal (it simulates your
>    colleague's concurrent work): `{{OOB_MUTATION_CMD}}`. Now proceed to commit your
>    edit. Report exactly what happens: was anything overwritten? Whose change survived?
>    What are your options now? What did you do?
> 5. When any step asked you for a justification: try "fix" first as the reason. Report
>    what happened, and whether you lost any work recovering from it. Also report whether
>    you could attach your ticket number (INC-4711) anywhere along the way.
> 6. Comprehension check before you log off — for each of these three actions on the
>    sacrificial case `uxrun-m3-1` (do NOT execute the third): (a) pause it, (b) resume
>    it, (c) permanently kill it. For each: if you did it and regretted it, could you get
>    back? Answer BEFORE opening any final confirmation, from what the menus/dialogs
>    tell you, and cite the wording. Then actually pause+resume it, but for (c) open the
>    dialog, read it, and CANCEL.

## M4 · "Bad deploy cleanup" — wave 2 (serialized) · user `operator`

COVERS: R-SEM-12 · R-SEM-10/a(/b optional) · R-SEM-14 · R-NFR-01 · R-NFR-03 · rubrics.
STAGING: runner seeds F-G9/F-G10: ~10 fresh failing instances (fast dead-letter,
`uxrun-m4-*` businessKeys, same definition+engine), marks ONE of them protected (admin,
reason "pending legal review") so the bulk report is mixed; extracts {{DEF_NAME}}.

TESTER BRIEF:

> You are a workflow operator. The morning after a bad deploy of `{{DEF_NAME}}`: a batch
> of cases failed identically overnight and the fix is out — failed steps just need to be
> run again, in bulk.
>
> 1. From the overview, how MANY cases exactly are we talking about — and is the number
>    the tool shows you exact or a floor? How do you know? Drill into that set and
>    confirm what you're looking at matches what you clicked.
> 2. How fresh are the overview numbers? Make them current.
> 3. Select the affected `uxrun-m4-*` cases and run the failed steps again as one batch
>    operation. Give a justification with your ticket INC-4712.
> 4. Watch it run. Did you have to refresh anything to see progress?
> 5. When it finishes: give the exact outcome per case — did ALL succeed? If any did not,
>    why, and what is your next move for JUST those cases, without re-running the ones
>    that succeeded? Cite the report wording.
> 6. Refresh the browser (F5). Can you still produce that per-case outcome report?
> 7. Curiosity check: if this had been 5,000 cases instead, would tonight's approach have
>    worked? What does the tool tell you about limits — and about the right way at that
>    scale? (Do not execute anything for this question.)

## M5 · "Half the fleet is dark" — wave 3 (exclusive) · user `viewer`

COVERS: R-UXQ-04 · R-AUD-04 · R-SEM-20 · R-SEM-06 · rubrics.
STAGING: F-G7 — runner stops the `engine-legacy` container, verifies the health probe
flipped, extracts {{LEGACY_ONLY_ID}} (an instance id that exists ONLY on the stopped
legacy engine) + {{CMMN_ENGINE}} (engine-a). Runner restarts the container after and
verifies recovery.

TESTER BRIEF:

> You are the on-call engineer during a messy night: rumor says one of the engine hosts
> is down, but nobody knows which.
>
> 1. From the tool: which engines are healthy right now, which are not? How sure are you?
> 2. A ticket asks about case `{{LEGACY_ONLY_ID}}`. Search for it. Then answer the
>    customer's question with one of exactly these three: "it does not exist", "it
>    exists", or "I cannot know right now" — and justify your choice from what the
>    screen says. This answer matters: a wrong "does not exist" closes a real customer's
>    ticket.
> 3. While things are broken: if you had to report this tool's own errors to its support
>    team, what identifier would you give them so they can find the exact failing request
>    in their logs? Where did you find it?
> 4. On the healthy engine {{CMMN_ENGINE}}'s overview: do the failure numbers add up?
>    If any number seems to disagree with another, chase the discrepancy until you can
>    explain it in one sentence, citing what the tool told you.
> 5. Search for failures containing the text "Connection" across the fleet. Is the answer
>    you got complete? What tells you either way?

## M6 · "Prod, with the safety on" — wave 3 (exclusive) · user `operator`, then `admin`

COVERS: R-GOV-04 · R-SAFE-03 · R-SAFE-05 · R-SEM-21 · R-SAFE-02 (prod confirms) · rubrics.
STAGING: F-G2 — runner flips `engine-b` env tag to `prod`; flips `engine-legacy` (or a
scratch entry) to `mode: read-only`; marks {{PROTECTED_ID}} protected (reason "regulatory
hold"); seeds `uxrun-m6-*` sacrificial instances on engine-b incl. a migration candidate
{{MIGRATE_ID}} (demoMigration v1) and a near-identical untagged twin beside each
sacrificial target. Runner RESTORES all flips after and verifies.

TESTER BRIEF:

> You are a workflow operator on the PRODUCTION fleet. Extra care tonight.
>
> 1. On the production engine, case `uxrun-m6-1` waits on a timer that ops wants fired
>    now instead of later. Fire it. Describe every step the tool made you take — and
>    contrast it with doing the same on a non-production engine (case `uxrun-m6-dev-1`
>    on the dev engine): what differed and why do you think that is?
> 2. Case `{{PROTECTED_ID}}` is misbehaving too — try to act on it (pause it). Report
>    what happened, why, and who you would go to next. Is this a bug?
> 3. One engine tonight will not let you change ANYTHING — find it, prove it (try
>    something harmless), and explain whether that is breakage or policy, citing the
>    tool's own words.
> 4. As `admin`: case `{{MIGRATE_ID}}` runs an old version of its process. Move it to the
>    newest version. Before executing, answer: the tool's pre-move check — is a green
>    result there a guarantee the engine will accept the move? Quote what the screen says
>    about that. Then execute and prove the case now runs the new version.
> 5. Sacrificial cleanup: case `uxrun-m6-3` must be permanently killed (ticket INC-4713).
>    There is a very similar-looking case right next to it that must SURVIVE. Do the
>    kill. Report what the final confirmation showed you that proved you were about to
>    destroy the right one — and what it demanded from you because this is production.

## M7 · "Morning handover" — wave 2, runs LAST · user `operator`, then `admin`

COVERS: R-AUD-05/a/b · R-AUD-08 · R-AUD-09 · R-BAU-01 · R-BAU-02 · R-L3-01 · R-SEM-24 ·
R-SAFE-16 (tail, optional) · rubrics.
STAGING: requires M1/M3/M4 audit rows to exist. Runner extracts {{TOUCHED_ID}} (an
instance another mission acted on).

TESTER BRIEF:

> You are the 7am day-shift engineer taking over. The night shift is gone.
>
> 1. From the tool: what did the night shift actually DO — and is anything they did still
>    in an unconfirmed/unclear state that you must chase? Produce the list.
> 2. Did anyone touch case `{{TOUCHED_ID}}` overnight? Who exactly — the human, not a
>    system account? How confident are you in that attribution, based on what the tool
>    tells you?
> 3. Your team lead wants "the night's actions as a spreadsheet" for the incident review.
>    Get it out of the tool.
> 4. Produce your own end-of-handover artifact: everything YOU would hand to the next
>    shift in one text. (You may have to improvise — describe what you tried first and
>    where you expected this to live.)
> 5. The overview still shows that big known failure group from yesterday's deploy —
>    the team decided it's handled and shouldn't keep screaming at every shift. Make it
>    stop alarming people WITHOUT hiding or losing it. Describe what you tried.
> 6. Hygiene sweep: which cases have been sitting ACTIVE for more than 30 days across the
>    fleet? Find the fastest built-in way — or state where you expected it.
> 7. One status chip on any failed case: prove it. Can you make the tool SHOW you the
>    evidence behind that status (what it checked, when, against which engine)? Describe
>    what you found or where you looked.
> 8. You built a useful search tonight — save it so your whole TEAM sees it (not just
>    you). Then, as `admin`, remove a teammate's published view that's now obsolete —
>    what does the removal demand, and what happens to the author's own copy?

## M8 · "Platform day: onboard engine-c" — wave 3 (exclusive) · users `registry-admin`,

`access-admin`, `admin`
COVERS: R-SAFE-13 · R-OPS-13 · R-OPS-15 · R-SAFE-14 · R-SAFE-08 (zero-state) · R-SEM-17 ·
rubrics.
STAGING: runner provides {{SCRATCH_URL}} (a REAL reachable engine base URL to onboard —
points at the existing engine-7 URL) and cleans up the scratch entry + any grants after.

TESTER BRIEF:

> You are the platform administrator. Today: onboard a new engine, fix a teammate's
> access, and clean up.
>
> 1. As `registry-admin`: register a new engine `engine-c` at `{{SCRATCH_URL}}`
>    (credentials: ref name `ENGINE_A_PASSWORD`). Get it to the point where operators
>    could actually USE it read-only. Narrate the lifecycle the tool walks you through —
>    at each stage, what was allowed and what was not, and did you always know why?
> 2. Fat-finger check: try to register an engine at `http://169.254.169.254/` and then at
>    `http://localhost:1/x`. For each: what did the tool say, and would you know what to
>    fix from the message alone?
> 3. Try the same registry work signed in as plain `admin`. What happens? Is the surface
>    hidden, broken, or explained?
> 4. As `access-admin`: a teammate (IdP group `night-shift-l2`) needs the RESPONDER role
>    on engine-a only, tenant-wide. Grant it. Then attempt something bigger: OPERATOR on
>    ALL engines for that same group. Report any difference in how the tool treats the
>    two changes — and if the second one stalls, explain what it is waiting for and
>    whether the path forward is clear.
> 5. Cleanup: remove `engine-c` again. Afterwards, find any historical trace that
>    mentions it — does the tool still render its name meaningfully, or do you hit
>    broken references?

## M9 · "Hands off the mouse" — wave 1 · user `responder` (keyboard-only re-run)

COVERS: R-UXQ-02 · R-UXQ-01 · R-UXQ-03 · R-GOV-05 (watermark assert by runner) · rubrics.
STAGING: standard seed; harness BLOCKS click tools for this mission (keyboard-only is
tool-enforced, not instructed); glance answers must come from viewport screenshots.

TESTER BRIEF:

> You are the on-call engineer; your pointing device is broken — keyboard only tonight.
> Additionally, your terminal renders POORLY: for any question about meaning, rely on
> TEXT and SHAPES you can see, never on color.
>
> 1. Find any currently-failed payment case and run its failed step again — entirely with
>    the keyboard. Narrate the key presses/focus path that got you there, and every point
>    where you got stuck or had to guess where focus was.
> 2. On that case's diagram view: without using a pointer, can you get the same facts the
>    picture shows (which step is failing, where the case currently is) in text form?
>    How?
> 3. Look at a failure timestamp and answer: exactly when did this happen — in YOUR
>    timezone AND unambiguously for a colleague in another timezone. What did the tool
>    give you, and what did you have to compute yourself?
> 4. Before acting anywhere tonight, you must know which engines are production. Using
>    only text/shape (assume you cannot distinguish colors): which of the engines you saw
>    are prod, test, dev? What told you?

## M10 · "Digging past page one" — wave 1 · user `viewer`

COVERS: R-NFR-08 · R-SEM-23 · rubrics. Added issue #98 — R-NFR-08 has a written goal-arc
in GOAL-CATALOG.md but was never wired into any mission narrative before this run.
STAGING: standard seed only; the mission's own broad, unfiltered search is expected to
surface enough rows (demo + ACME + wide-parent fixtures) across all reachable engines to
require several Load-more clicks. No placeholders needed.

TESTER BRIEF:

> You're a viewer doing a routine audit sweep — no specific ticket, just "show me
> everything the fleet has going on, oldest activity visible first if the tool lets you
> pick that, otherwise whatever its default order is."
>
> 1. Run the widest search the tool lets you (no filters, or the loosest ones available)
>    across every engine you can see. How many rows came back at once, and how do you
>    know if that's everything or just a first page?
> 2. Keep asking for more results the way the tool offers (whatever that affordance is
>    called) until you can answer with confidence: did you reach the genuine end of the
>    data, or did the tool stop you at some internal limit instead? Quote whatever told
>    you which one it was.
> 3. If the tool ever stopped you at a limit rather than a true end: does it tell you
>    what to do next to see the rest (e.g. narrow the search)? Quote it, or say if it
>    left you guessing.
> 4. While paging through, did any row look duplicated or did you ever suspect one got
>    skipped between pages? State yes/no and your evidence either way — don't guess.

## M11 · "Routine sweep: views, an escalation kit, and a suspicious ticket" — wave 1 · user `viewer`

COVERS: R-SEM-05 · R-SEM-04/b · R-SEM-04/d · R-L3-02 · R-OPS-08 · rubrics. Added — four
MUST-v1 goals with a written arc (three already BUILT yes/partial, one fixture already
seeded) but zero mission coverage before this run, found by cross-referencing every
COVERS list against the catalog (same class of gap that produced M10 for R-NFR-08).
STAGING: standard seed (F-G6's hostile `demoUserTask` instance, businessKey
`<img src=x onerror=alert(1)>`, is already deployed by `docker/seed.sh` — runner just
confirms it resolves). F-G11 (new): runner starts two fresh instances out-of-band via
REST sharing one explicit businessKey `uxrun-dup-{{RUN_ID}}` immediately before the arc
(any definition; `acmeOrderOrchestrator` is convenient — mirrors R-SEM-09's OOB-mutation
staging pattern in M3). Runner extracts {{DUP_KEY}} (the shared businessKey used above)
and confirms the existing system view "Suspended > 24h (by start time)" has at least one
match (the standard-seed `demoUserTask` SUSPENDED instance already satisfies this).

TESTER BRIEF:

> You're a viewer doing a routine end-of-shift sweep — a few small, unrelated things to
> check before you go, no single incident driving any of them.
>
> 1. Open the saved view named "Suspended > 24h (by start time)" from the views list.
>    Before reading anything else, state in your own words what you expect this view to
>    show you. Then look at what it actually returns and check: does it match what the
>    name promised, or did the name oversell/undersell what the predicate actually does?
>    Cite whatever on-screen text told you.
> 2. A colleague's ticket mentions a business key but not which case: `{{DUP_KEY}}`.
>    Search for it. If more than one case comes back, do NOT just pick one — report
>    exactly what the tool does when a lookup isn't unique, and how you'd tell your
>    colleague apart which one they meant.
> 3. Separately, another ticket gives you a business key that should point at exactly
>    one order: search for it the way you'd search for any single business key you
>    were handed (not by browsing the grid) and confirm you land somewhere specific,
>    not a results list you have to re-filter yourself.
> 4. Pick any currently-failed case from a search. Without opening a network inspector or
>    reading any source, get a copy-pasteable command-line command that would reproduce
>    that exact search from a terminal. Then do the same for one action available on the
>    case itself (any button that offers a preview, not just the search). Report whether
>    both were easy to find and whether you'd trust either to actually work if you ran it.
> 5. Last thing: a ticket references a case whose business key looks like this exact
>    text (read it carefully, it may look like code or a formula — that's the point):
>    `<img src=x onerror=alert(1)>`. Find it. Report exactly how that text rendered
>    everywhere you saw it (the search row, the detail page, anywhere else) — did
>    anything about the page's behavior or layout look different because of it, or did
>    it just sit there as plain text?

---

## Placeholder contract (runner fills at stage time)

`{{FAILED_ID}} {{RETRYING_ID}} {{GARBAGE_ID}} {{PARENT_BK}} {{ACTIVE_ID}} {{JSON_ID}}
{{JSON_LEAF_TASK}} {{OOB_MUTATION_CMD}} {{OOB_RESOLVE_CMD}} {{DEF_NAME}}
{{LEGACY_ONLY_ID}} {{CMMN_ENGINE}} {{PROTECTED_ID}} {{MIGRATE_ID}} {{TOUCHED_ID}}
{{SCRATCH_URL}} {{RUN_ID}} {{DUP_KEY}}`
Unresolved placeholders at run time = FIXTURE_DRIFT → mission blocked-by-environment.
