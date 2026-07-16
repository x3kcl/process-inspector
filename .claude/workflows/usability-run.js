export const meta = {
  name: 'usability-run',
  description: 'Goal-based usability run: stage fixtures, drive 11 incident missions with naive Sonnet testers via Playwright MCP, ground-truth-verify, reconcile to a gate verdict',
  whenToUse: 'Prove the inspector ergonomics against docs/usability/GOAL-CATALOG.md — on demand or nightly. Requires the dev stack (engines + BFF :8085 + Vite :5173) up and the playwright MCP connected.',
  phases: [
    { title: 'Stage', detail: 'verify stack, seed, extract placeholders, seed uxrun cohorts' },
    { title: 'Missions', detail: 'serialized naive testers (one shared browser)' },
    { title: 'Evaluate', detail: 'ground-truth merge + reconciliation + gate verdict' },
  ],
}

// ---- parameters (all overridable via args) ------------------------------------------
const REPO = args?.repo ?? '/home/flapci/workspace/pi-usability'
const APP = args?.app ?? 'http://localhost:5173'
const BFF = args?.bff ?? 'http://localhost:8085'
const RESULTS_DIR = args?.resultsDir ?? `${REPO}/docs/usability/results/latest`
const RUN_ID = args?.runId ?? 'adhoc'
// Wave order is load-bearing: read-only first, mutating serialized, fleet-staging last,
// M7 after M1/M3/M4 (it consumes their audit rows). See GOAL-CATALOG "RUN PROTOCOL".
// M11 added (issue #205 usability-coverage extension) — wave 1, read-only, same class as
// M10's own addition in #98; missing from this list until now (a real gap, not deliberate).
const MISSIONS = args?.missions ?? ['M1', 'M2', 'M9', 'M10', 'M11', 'M3', 'M4', 'M7', 'M5', 'M6', 'M8']
const TESTER_MODEL = args?.testerModel ?? 'sonnet'

const MISSION_USER = {
  M1: 'responder', M2: 'responder', M9: 'responder', M10: 'viewer', M11: 'viewer',
  M3: 'operator', M4: 'operator', M7: 'operator',
  M5: 'viewer', M6: 'operator', M8: 'registry-admin',
}

// Log the resolved targets up front — issue #215 (2026-07-16 run) found that an `args`
// object CAN silently fail to reach this script (every param fell back to its default,
// including RUN_ID, even though `args` was passed correctly to the Workflow tool call),
// which sent the entire mission fleet at the shared default stack instead of an intended
// isolated instance without any error. This is the cheapest possible tripwire: the
// mismatch is visible in the progress log within seconds instead of discovered after a
// multi-hour run completes.
log(`Targets: repo=${REPO} app=${APP} bff=${BFF} runId=${RUN_ID} missions=${MISSIONS.join(',')}`)

// ---- schemas -------------------------------------------------------------------------
const STAGE_SCHEMA = {
  type: 'object', required: ['ok', 'placeholders'],
  properties: {
    ok: { type: 'boolean' },
    placeholders: { type: 'object', additionalProperties: { type: 'string' } },
    seedFingerprint: { type: 'string' },
    notes: { type: 'array', items: { type: 'string' } },
    degraded: { type: 'array', items: { type: 'string' } },
  },
}
const TESTER_SCHEMA = {
  type: 'object', required: ['mission', 'tasks'],
  properties: {
    mission: { type: 'string' },
    user: { type: 'string' },
    tasks: {
      type: 'array', items: {
        type: 'object', required: ['n', 'verdict'],
        properties: {
          n: { type: 'integer' },
          verdict: { enum: ['yes', 'yes-with-struggle', 'no', 'blocked-by-environment'] },
          answer: { type: 'string', description: 'your conclusion/answer for the task, 1-3 sentences' },
          citations: { type: 'array', items: { type: 'string' }, description: 'verbatim on-screen text that grounded the answer' },
          interactions: { type: 'integer' },
          firstSignal: { type: 'string', description: 'what first told you it worked / you were on track' },
          wrongTurns: { type: 'array', items: { type: 'string' } },
          confusion: { type: 'array', items: { type: 'string' }, description: 'verbatim misleading/unclear on-screen text + why' },
          gaveUpWhere: { type: 'string', description: 'on verdict=no: where you expected the affordance to live' },
        },
      },
    },
    messagesCorpus: { type: 'array', items: { type: 'object', properties: { quote: { type: 'string' }, tag: { enum: ['fine', 'confusing'] } } } },
    protocolNotes: { type: 'array', items: { type: 'string' } },
  },
}
const HOOK_SCHEMA = {
  type: 'object', required: ['ok'],
  properties: {
    ok: { type: 'boolean' },
    groundTruth: { type: 'array', items: { type: 'string' }, description: 'engine-verified facts about what the tester actually changed' },
    restored: { type: 'boolean' },
    notes: { type: 'array', items: { type: 'string' } },
  },
}
const RECON_SCHEMA = {
  type: 'object', required: ['gate', 'themes'],
  properties: {
    gate: { enum: ['pass', 'fail', 'fail-expected-gaps-only'] },
    themes: { type: 'array', items: { type: 'object', required: ['title', 'sev'], properties: { title: { type: 'string' }, sev: { enum: ['Sev1', 'Sev2', 'Sev3', 'Sev4'] }, surfaces: { type: 'integer' }, evidence: { type: 'string' } } } },
    missionVerdicts: { type: 'array', items: { type: 'string' } },
    reportPath: { type: 'string' },
  },
}

// ---- shared prompt fragments ---------------------------------------------------------
const PROTOCOL = `
TESTER PROTOCOL (mandatory):
- You are a NAIVE first-time user of this tool. You have general engineering literacy but
  you must ground EVERY claim in on-screen text you actually saw — quote it. An answer
  without a citation is worthless. Do not use prior knowledge of workflow engines to skip
  reading the UI.
- Drive the REAL UI via the playwright MCP tools (load them via ToolSearch: browser_navigate,
  browser_snapshot, browser_click, browser_fill_form, browser_press_key, browser_take_screenshot,
  browser_evaluate). Never read the app's source code. Never call its HTTP API directly —
  EXCEPT commands this brief explicitly hands you to run.
- SETUP (allowed, not part of the test): navigate to the app; if a previous user's session
  is active (check the header/user identity), sign out if you can find it, else
  browser_evaluate sessionStorage.clear() + localStorage.clear() and reload until you get
  the sign-in form. Sign in as the user stated below (password: dev).
- Before every interaction state: "I expect <action> will <outcome>". Wrong turns are data.
- GIVE-UP RULE (hard): a task ends verdict=no at the FIRST of: 15 UI interactions for that
  task, 3 distinct strategies exhausted, 2 consecutive interactions yielding nothing new,
  or the same element tried twice with the same result. On give-up record your last
  hypothesis and WHERE you expected the affordance to live. Giving up honestly is a GOOD
  outcome; brute-forcing ruins the data.
- NEVER re-submit an action that changes data because you saw no feedback — if you are
  tempted to, that is itself a finding (record it as such and move on).
- Quote verbatim every notable message you meet (errors, confirmations, banners) in
  messagesCorpus, tagged fine/confusing.
- Your structured output is the deliverable: fill every field of the schema per task.`

function testerPrompt(mission, user, brief) {
  return `You are a support engineer usability-testing an internal "Process Inspector" web
app at ${APP}. Sign in as user "${user}" (password "dev").
${PROTOCOL}

YOUR MISSION (${mission}):
${brief}`
}

// ---- Phase: Stage ----------------------------------------------------------------------
phase('Stage')
log('Verifying stack, seeding, extracting placeholders')
const stage = await agent(`You are the STAGER for a usability run of the Process Inspector.
Work with Bash from repo ${REPO}. Engines: engine-a http://localhost:8081, engine-b :8082,
engine-7 :8083, engine-legacy :8084 (all /flowable-rest/service, creds rest-admin:test).
BFF ${BFF} (dev users viewer/responder/operator/admin/registry-admin/access-admin, pw dev,
Basic auth works; the Authorization header is CSRF-exempt). Frontend ${APP}.

DO, in order:
1. Verify: BFF /actuator/health UP; frontend ${APP} returns 200; engines a/b/legacy reachable. Any failure => ok:false with notes.
2. Run: bash ${REPO}/docker/seed.sh  (idempotent; seeds demo+ACME+wide-parent+hostile fixtures).
3. Compute seedFingerprint: sha256 of the sorted list of (definitionKey,total) across engine-a.
4. Extract placeholders as a flat object of STRINGS (query engine REST; prefer engine-a; every id must be verified to exist, format engineId:instanceId ONLY where noted):
   - FAILED_ID: a demoFailingPayment instance id (bare uuid) that currently HAS a dead-letter job on engine-a.
   - RETRYING_ID: a demoFailingRetry instance id on engine-a (has a failing timer job, no dead-letter).
   - GARBAGE_ID: a random well-formed UUID v4 that exists on NO engine (verify).
   - PARENT_BK: the businessKey of the seeded demoParent instance on engine-a whose CHILD dead-lettered (starts with "seed-").
   - ACTIVE_ID: the ACTIVE (not suspended) demoUserTask instance id on engine-a that has variables amount+note. If none has them, PUT them onto the engine (amount integer 100, note string "temporary hold") via engine REST and use that instance.
   - JSON_ID: an acmeVendorEnrichment (or any ACME) instance id on engine-a carrying a json/structured variable; set JSON_LEAF_TASK to a one-line instruction naming one boolean/scalar leaf inside it to flip (inspect the variable to pick a real leaf). If no json variable exists anywhere, create one: PUT a json variable "config" {"retry":{"enabled":true,"max":3}} on ACTIVE_ID's instance over engine REST, set JSON_ID=ACTIVE_ID and JSON_LEAF_TASK="inside variable config: retry.enabled must become false".
   - OOB_MUTATION_CMD: a single curl command that edits variable amount on ACTIVE_ID AS ANOTHER USER through the BFF: curl -su admin:dev -H 'Content-Type: application/json' -X POST ${BFF}/api/instances/engine-a/<ACTIVE_ID>/actions/edit-variable -d '{"reason":"colleague simulation INC-9999 correcting amount","variable":{"name":"amount","type":"integer","value":275,"expectedOldValue":250}}'  — VERIFY the route+payload shape against ${REPO}/backend/src/main/java/io/inspector/api/CorrectiveActionController.java and action/ActionRequest.java, and adjust so it WOULD succeed after the tester first set amount=250 (do NOT execute it now).
   - DEF_NAME: "demoFailingPayment" (the M4 cohort definition).
   - LEGACY_ONLY_ID: an instance id that exists ONLY on engine-legacy (:8084) — e.g. a demoOrder completed instance there; verify it is absent from a/b/7.
   - CMMN_ENGINE: "engine-a".
   - PROTECTED_ID / MIGRATE_ID / TOUCHED_ID: see below.
   - DUP_KEY (M11 / F-G11): no natural duplicate businessKey exists in the standard seed. Start TWO fresh acmeOrderOrchestrator instances on engine-a sharing one EXPLICIT businessKey "uxrun-dup-<short-random-suffix>" (verify both exist and share that exact key). Set DUP_KEY to that shared businessKey.
   - OOB_RESOLVE_CMD (M3 / R-SEM-09/b, issue #226): a single curl command that TERMINATES the uxrun-m3-2 instance AS ANOTHER USER through the BFF (if uxrun-m3-2 resolves to more than one instance, target the most-recently-started one — same F-G10 defensive convention as PARENT_BK/ACTIVE_ID resolution): curl -su admin:dev -H 'Content-Type: application/json' -X POST ${BFF}/api/instances/engine-a/<uxrun-m3-2 instance id>/actions/terminate-delete -d '{"reason":"colleague already handled this one - INC-4713"}' — VERIFY the route+payload shape against ${REPO}/backend/src/main/java/io/inspector/api/CorrectiveActionController.java and action/ActionRequest.java (terminate-delete is tier 3/ADMIN; on engine-a, a DEV engine, no confirmToken is required — only reason ≥10 chars). Do NOT execute it now; the tester runs it themselves.
5. Seed run cohorts over engine REST — DEDUPE BY BUSINESS KEY FIRST (issue #227, F-G10):
   before creating ANY uxrun-<mission>-<n> instance below, query
   /runtime/process-instances?businessKey=<key> on its target engine FIRST. If an ACTIVE
   instance with that exact key already exists, REUSE it (do not create a second one) —
   only create when none exists. Repeated runs against a not-freshly-reset dev stack have
   accumulated 2-3 duplicate instances under the same "sacrificial, singular" businessKey
   (e.g. uxrun-m3-1), forcing testers to guess which one a mission's narrative meant; this
   check-first discipline is what prevents that, not a one-time cleanup.
   - uxrun-m3-1 and uxrun-m3-2: two demoUserTask instances on engine-a (businessKeys exactly those).
   - uxrun-m4-1..8: eight demoFailingPayment instances on engine-a (amount 100, divisor 0) with those businessKeys — they dead-letter in ~40s.
   - uxrun-m6-1: demoTimerWait on engine-b (dueDuration PT24H); uxrun-m6-dev-1: same on engine-a; uxrun-m6-3 AND uxrun-m6-3-twin: two demoUserTask on engine-b (twin must look near-identical, businessKey uxrun-m6-3t); MIGRATE_ID: a demoMigration v1... check: definition key from ${REPO}/docker/processes/demo-migration-v1.bpmn20.xml (grep the process id); start one instance of the OLD version on engine-b, businessKey uxrun-m6-mig.
   - PROTECTED_ID: pick uxrun-m4-8; try to mark it protected via the BFF (look for a protected-instances route: grep -rn "protected" ${REPO}/backend/src/main/java/io/inspector/api/ — if a route exists, mark as admin:dev with reason "regulatory hold - usability fixture"; if none exists, note it in degraded and leave unprotected).
   - TOUCHED_ID: set equal to ACTIVE_ID (M3 will act on it before M7 runs).
6. Return ok:true with ALL placeholders present (string values), seedFingerprint, notes (what you staged), degraded (anything you could not stage).
Be surgical; total budget ~40 tool calls.`, { schema: STAGE_SCHEMA, label: 'stage' })

if (!stage || !stage.ok) {
  return { gate: 'fail', reason: 'staging failed', notes: stage?.notes ?? ['stager returned null'] }
}
log(`Staged. degraded=${JSON.stringify(stage.degraded ?? [])}`)

// M8's STAGING note pins this to the existing engine-7 base URL specifically ("a REAL
// reachable engine base URL to onboard — points at the existing engine-7 URL") — a plain
// script-level constant, not something the stager agent needs to discover, so it's merged
// in here rather than added to the stage prompt (keeps the stage agent() call's cache key
// unchanged across re-runs of just this step).
const placeholders = { ...stage.placeholders, SCRATCH_URL: 'http://localhost:8083/flowable-rest/service' }

// Mission briefs live in docs/usability/MISSIONS.md — a reader agent extracts + fills them
// (the workflow script itself has no fs access). Schema is an ARRAY of {id, brief}, not a
// free-form {additionalProperties: string} dictionary — a bare additionalProperties-only
// object schema (no enumerated `properties`) is unreliable for structured-output tool
// calling: it was observed coming back wrapped as {input: "<stringified JSON>"} instead of
// the requested top-level mapping, silently breaking every briefs?.[m] lookup below. An
// array-of-objects schema has concrete named properties and round-trips reliably.
const briefsList = await agent(`Read ${REPO}/docs/usability/MISSIONS.md. Extract each mission's
TESTER BRIEF block (the quoted lines after "TESTER BRIEF:", strip the leading "> ").
Substitute these placeholder values verbatim for {{NAME}} tokens: ${JSON.stringify(placeholders)}.
Return one entry per mission id found in the file (e.g. M1, M2, ... M10 if present) with the
fully substituted brief STRING. Every {{...}} token must be resolved — if one has no value,
replace it with the literal string MISSING and mention it.`, {
  schema: {
    type: 'object', required: ['missions'],
    properties: {
      missions: {
        type: 'array',
        items: {
          type: 'object', required: ['id', 'brief'],
          properties: { id: { type: 'string' }, brief: { type: 'string' } },
        },
      },
    },
  },
  label: 'briefs', effort: 'low',
})
const briefs = Object.fromEntries((briefsList?.missions ?? []).map((m) => [m.id, m.brief]))

// ---- Phase: Missions -------------------------------------------------------------------
phase('Missions')
const results = []
const hooks = []
for (const m of MISSIONS) {
  const brief = briefs?.[m]
  if (!brief || brief.includes('MISSING')) {
    log(`${m}: brief unresolved -> blocked-by-environment`)
    results.push({ mission: m, tasks: [], protocolNotes: ['FIXTURE_DRIFT: unresolved brief/placeholders'] })
    continue
  }

  // pre-stage hooks (fleet staging, exclusive wave)
  if (m === 'M5') {
    await agent(`Stage F-G7: docker stop process-inspector-engine-legacy-1 ; then poll ${BFF}/api/engines (curl -su viewer:dev) until engine-legacy reports unreachable (max 90s). Return ok:true when the strip shows it down.`, { schema: HOOK_SCHEMA, label: 'stage:M5', phase: 'Missions', effort: 'low' })
  }
  // Issue #227: F-G2 (tagging engine-b prod) is now KNOWN STRUCTURALLY IMPOSSIBLE against
  // this dev-only, plain-http engine — RegistryUrlValidator hard-rejects prod+non-https
  // before any hostname resolution (confirmed 2026-07-16, see issue #215's closing
  // comment). This is NOT a transient staging miss to retry; it never applies. So this
  // pre-flight check does NOT abort the whole M6 dispatch (tasks 2-5 — R-GOV-04, R-SEM-21,
  // R-SAFE-02, R-SAFE-05 — don't depend on F-G2 at all and have produced valid evidence in
  // every run to date; aborting the mission would throw that away for nothing). Instead:
  // task 1 is answered by the runner directly (no tester interactions wasted probing a
  // fixture that can never land), and the tester is told to start at task 2.
  let m6PreflightBlocked = null
  if (m === 'M6') {
    const stageM6 = await agent(`Stage F-G2 for mission M6. Using the BFF admin registry API (discover the exact routes in ${REPO}/backend/src/main/java/io/inspector/api/AdminEnginesController.java; auth registry-admin:dev):
1. Set engine-b environment tag to "prod". 2. Set engine-legacy mode to "read-only" (if legacy is not editable, use engine-7 and note it). Record EXACTLY what you changed (before->after) in notes so it can be restored. Return ok:true only if the flips are live (verify via GET).`, { schema: HOOK_SCHEMA, label: 'stage:M6', phase: 'Missions' })
    if (!stageM6 || stageM6.ok !== true) {
      m6PreflightBlocked = {
        n: 1,
        verdict: 'blocked-by-environment',
        answer:
          'R-SAFE-03 not evaluable this run: engine-b cannot be tagged prod through the registry API — RegistryUrlValidator rejects environment=PROD unless scheme=https (checked before any hostname resolution), and every dev-only engine here serves plain http. This is a permanent structural limitation (deliberate SSRF guardrail, R-OPS-13), not a re-stageable fixture gap — see issue #215. Deferred to the R-OPS-16 prod-like leg.',
        citations: [],
        interactions: 0,
        firstSignal: 'runner pre-flight GET confirmed engine-b.environment != prod before dispatch',
      }
      log('M6 task 1 pre-blocked (F-G2 structurally unstageable) — tester starts at task 2, no interactions spent on task 1')
    }
  }

  log(`Running ${m} (user ${MISSION_USER[m]})`)
  const effectiveBrief =
    m6PreflightBlocked !== null
      ? `${brief}\n\nRUNNER NOTE: task 1's fixture (a genuinely prod-tagged engine-b) could not be staged this run — this is a known, permanent environment limitation (issue #215), already recorded. SKIP task 1 entirely and start your numbered tasks at task 2; do not attempt or comment on task 1.`
      : brief
  const r = await agent(testerPrompt(m, MISSION_USER[m], effectiveBrief), {
    model: TESTER_MODEL, schema: TESTER_SCHEMA, label: `tester:${m}`, phase: 'Missions',
  })
  // The synthetic task 1 is authoritative — drop any n:1 the tester returned anyway
  // despite the RUNNER NOTE (an LLM not perfectly following the skip instruction),
  // rather than risk two competing task-1 entries confusing the reconciler downstream.
  const rWithPreflight =
    r !== null && m6PreflightBlocked !== null
      ? { ...r, tasks: [m6PreflightBlocked, ...(r.tasks ?? []).filter((t) => t.n !== 1)] }
      : r
  results.push(rWithPreflight ?? { mission: m, tasks: [], protocolNotes: ['tester died'] })

  // post-stage hooks: ground truth + restoration
  if (m === 'M3') {
    hooks.push(await agent(`Ground-truth check after mission M3 (variable edits on engine-a instance ${stage.placeholders.ACTIVE_ID}): via engine REST (rest-admin:test, :8081) read variables amount and note on that instance and report their CURRENT values; state whether amount is 250/275/300 and whether note is absent/null/empty-string. Also report the last 5 BFF audit rows for that instance if an audit API exists (curl -su admin:dev ${BFF}/api/audit?instanceId=... — discover params from ${REPO}/backend/src/main/java/io/inspector/api/AuditController.java). Also, for R-SEM-09/b (issue #226): confirm via engine REST that the uxrun-m3-2 instance targeted by OOB_RESOLVE_CMD is actually ended (historic endTime set, deleteReason present) and that the BFF audit trail attributes the termination to admin BEFORE the tester's own task 7 timestamp, not to the tester's own user — a termination attributed to the tester's own account would mean OOB_RESOLVE_CMD never actually ran ahead of them (a quiet-lie risk on this specific arc). Return facts in groundTruth.`, { schema: HOOK_SCHEMA, label: 'truth:M3', phase: 'Missions', effort: 'low' }))
  }
  if (m === 'M4') {
    hooks.push(await agent(`Ground-truth check after mission M4: via engine REST on engine-a, for the 8 instances with businessKeys uxrun-m4-1..8 (definition demoFailingPayment): how many still hold dead-letter jobs, how many are gone/completed? (They fail again on retry since divisor=0 — expect them back in the DLQ or executing.) Return counts in groundTruth.`, { schema: HOOK_SCHEMA, label: 'truth:M4', phase: 'Missions', effort: 'low' }))
  }
  if (m === 'M5') {
    hooks.push(await agent(`Restore after M5: docker start process-inspector-engine-legacy-1 ; poll engine :8084 /flowable-rest/service/management/engine (rest-admin:test) until 200 (max 120s); then poll ${BFF}/api/engines until legacy is reachable again. Return restored:true only on verified recovery.`, { schema: HOOK_SCHEMA, label: 'restore:M5', phase: 'Missions', effort: 'low' }))
  }
  if (m === 'M6') {
    hooks.push(await agent(`Restore + ground-truth after M6:
1. GROUND TRUTH via engine REST on engine-b: (a) is instance with businessKey uxrun-m6-3 gone (terminated) and its twin uxrun-m6-3t STILL ALIVE? (b) what processDefinitionId does the instance with businessKey uxrun-m6-mig now run (did it migrate to v2)? (c) does uxrun-m6-1 still wait on its timer or did it advance (timer fired)?
2. RESTORE registry flips: engine-b environment back to its pre-M6 value (check git-tracked ${REPO}/backend/src/main/resources/application.yml for the original tag, likely test), engine-legacy/engine-7 mode back to read-write — via the admin API (registry-admin:dev), verify via GET.
Return groundTruth facts + restored:true only when verified.`, { schema: HOOK_SCHEMA, label: 'restore:M6', phase: 'Missions' }))
  }
}

// ---- Phase: Evaluate ---------------------------------------------------------------------
phase('Evaluate')
log('Reconciling findings against the evaluator catalog')
const recon = await agent(`You are the RECONCILER for a usability run of the Process Inspector.
Inputs:
1. The evaluator catalog (rubrics, BUILT flags, exit gate, severity taxonomy): read ${REPO}/docs/usability/GOAL-CATALOG.md — especially "RUN PROTOCOL" and "Known-absent surfaces".
2. Mission->goal coverage: read the COVERS lines in ${REPO}/docs/usability/MISSIONS.md.
3. Tester results (structured JSON, one per mission): ${JSON.stringify(results)}
4. Ground-truth/restoration hooks: ${JSON.stringify(hooks)}

DO:
1. GRADE each mission task against the catalog rubrics (citation-or-nothing: uncited answers score unsupported). Apply the hallucination canary: any tester claiming success on a BUILT-no surface (the 6 MUST-v1 gaps) is flagged and their run devalued. Cross-check every claimed fix against the ground-truth hook facts — a UI-claimed success contradicted by engine state is a Sev1 quiet-lie finding.
2. CLUSTER all findings/confusions across missions by ROOT CAUSE (not by page); a theme hitting >=3 surfaces outranks any single-surface major. Apply the R-TEST-03 taxonomy: quiet lie / guard bypass / wrong-target / invisible apply = Sev1.
3. SCORE the exit gate per the catalog's RUN PROTOCOL (gate population = MUST-v1 & BUILT yes/partial & UI/feasible-staged; expected-fails excluded from numerator AND denominator, graded on evidence quality only; verdict "fail-expected-gaps-only" if the ONLY misses are the 6 known gaps).
4. WRITE these files (create dir ${RESULTS_DIR}):
   - ${RESULTS_DIR}/results.jsonl — one line per goal-arc x mission following the catalog's RESULT SCHEMA (runId "${RUN_ID}", catalogVersion "1.0", seedFingerprint "${stage.seedFingerprint ?? ''}"; get bff sha from curl -s ${BFF}/api/meta if it exists, else git rev-parse HEAD in ${REPO}).
   - ${RESULTS_DIR}/RUN-REPORT.md — the human report: gate verdict; per-mission task table (verdict + one-line evidence); themes ranked by severity with element citations; the 6 known-gap evidence paragraphs (what testers tried, where they expected the affordance); rubric-corpus verdict (R-UXQ-05/06 violations with quotes); protocol violations; environment/staging notes (degraded: ${JSON.stringify(stage.degraded ?? [])}).
5. SANITIZE before writing (the CI security audit greps tracked files for any URL whose
   authority section carries user-colon-password before the host, regardless of value):
   written artifacts must contain NO credential-in-URL form at all — describe such
   attempts in words ("a credential-in-URL attempt") instead of quoting the literal;
   never write real secret values (dev-ladder passwords included).
6. Return: gate, top themes (max 12, ranked), missionVerdicts (one line each "M1: 6/7 yes ..."), reportPath.`, { schema: RECON_SCHEMA, label: 'reconcile' })

return {
  gate: recon?.gate ?? 'fail',
  themes: recon?.themes ?? [],
  missionVerdicts: recon?.missionVerdicts ?? [],
  report: recon?.reportPath ?? `${RESULTS_DIR}/RUN-REPORT.md`,
  staged: stage.notes,
  degraded: stage.degraded ?? [],
}
