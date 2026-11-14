---
name: tdd
description: The RED → GREEN → REFACTOR cycle every process-inspector code change follows — write the failing test FIRST, run it, prove it fails for the RIGHT reason, commit it as `test(#N): RED — …`, then the smallest production change that turns it green, then refactor on green. Read BEFORE editing backend/src/main, frontend/src or scripts/ — not after. Covers the four things a RED test must pin, the "fails to compile is not RED" trap, where the RED proof is recorded (the pull-request body), the combined RED-set run, and what is ENFORCED versus doctrine. Defers HOW to write the test (rungs, fixtures, fast loop) to `unit-test-patterns` and the dockerized-engine layer to `engine-harness`.
---
# TDD — RED → GREEN → REFACTOR, in this repo's terms

*Ported from the flap `tdd` skill; layers, paths and commands adapted to this codebase.*

process-inspector is worked by parallel agents; a test written *after* the code proves only that the code does
what it does. The test written *before* it is the spec. Every behaviour change — Java, TypeScript, bash — lands
as **a RED commit, then a GREEN commit**, and the pull-request body names the RED proof. This skill is the WHEN
and the WHAT-TO-PIN; `unit-test-patterns` is the HOW (rungs, fixtures, `-Dtest` loop), `engine-harness` is the
dockerized-engine layer. The rules the merge queue will check are in [`docs/landing-rules.md`](../../../docs/landing-rules.md).

---

## The cycle (no human pause between phases)

1. **RED.** Write the test. Run it. It must fail **on an assertion** (or an exit code the test asserts), not on
   a compile error, a missing bean, an NPE in setup, or a wrong fixture — a test that cannot reach its assertion
   proves nothing about the behaviour. For a brand-new type, stub the type so it compiles (methods throw
   `UnsupportedOperationException`), then see the assertion fail. Commit the test alone:
   `test(#N): RED — <what the test pins>`.
2. **GREEN.** The smallest production change that makes it pass. Nothing speculative — no second code path the
   test does not pin. Run the driven class, then the wider suite before claiming done (CLAUDE.md "Build &
   test"). Commit: `feat|fix(<scope>): … (Closes #N)`.
3. **REFACTOR.** Only on green. Remove duplication, name things, pull a seam — the tests do not change unless
   the refactor changes a public contract, and then the contract change is its own RED first.

Agents run this unattended: **never stop after RED to "wait for confirmation"** — the confirmation is the
test's own failure output, which you quote in the pull-request body (below). A session that cannot run the
test (no Maven, no Docker, no engines) states that explicitly instead of claiming RED.

Commands for the RED and GREEN runs:

```bash
cd backend && mvn -o test -Dtest=TheClassYouAreDriving      # backend, one class
cd frontend && npx vitest run src/actions/guard.test.ts      # frontend, one file
cd frontend && npx playwright test e2e/deadletter-delete.spec.ts   # e2e, one spec (BFF + Vite up)
bash scripts/<x>-selftest.sh                                 # a bash selftest
```

## What a RED test must pin — the four questions

Ask them for every change; write the cases the code actually has, not a ritual matrix (a one-line delegating
method does not get twelve boundary tests — YAGNI applies to tests too).

1. **Value ranges and boundaries.** Nominal input; the exact edges; one step outside each edge. The inspector
   has thresholds (paging caps, `size=1` counts, retry budgets, retention windows, truncation limits): a
   threshold test pins `t`, `t - 1` and `t + 1`, and says which side is inclusive. A boundary nobody tested is
   a boundary nobody decided.
2. **Corner cases.** Empty (`null`, `""`, `List.of()`, zero rows); extremes (`total > fetched`, a page at the
   cap, same-tick timestamps — set explicit instants, never `Instant.now()` in a loop); contextual (duplicate
   composite ids, an engine that is down ⇒ `perEngine.ok=false` envelope not an exception, a disabled engine,
   an engine version without the capability).
3. **Exact expected output.** A deterministic value, a state mutation (the row that changed AND the rows that
   did not), or a failure shape: `assertThatThrownBy(...).isInstanceOf(X.class).hasMessageContaining("<the
   decisive fragment>")` — pin the class and the fragment that carries the meaning, not the whole sentence.
4. **Proof, not presence.** Given / When / Then, visibly separated. `assertThat(x).isEqualTo(expected)`, never
   `isNotNull()` as the only assertion. On the frontend, assert what the user sees (`getByRole`, text, the
   request sent), not that a component rendered.

## Where the RED proof lives

In the pull-request body, two lines:

```
RED: <test class / spec / selftest> <sha of the RED commit>
RED proof: <the assertion message / exit line the test printed before the fix>
```

For a guard (an ArchUnit rule, a selftest), the proof is the **planted mutant** that made it fail — add the
forbidden call, drop the row, bump the count — run it, quote the failure, remove the mutant.

## Per-layer RED

Use the lowest rung that proves the behaviour (`unit-test-patterns` has the ladder).

| Change | RED test lives in | How it fails red |
|---|---|---|
| Status derivation, merge/sort, filter composition, id parsing | rung 1: pure static test in `backend/src/test/java/`, no Spring, no mocks (e.g. `ForwardedActorTest`) | assertion |
| Engine HTTP client behaviour (auth, timeout → envelope, paging caps) | rung 2: stubbed HTTP — `WireMockServer` (e.g. `client/ProcessApiClientTest`) | assertion on the parsed result or the recorded request |
| Wiring, RBAC route rules, YAML binding | rung 3: cached `@SpringBootTest` (e.g. `api/ProtectedInstanceRbacSpringTest`) | asserted status code / body fragment |
| Anything that depends on a REAL Flowable response shape (DLQ/suspended join, actions) | rung 4: `*IT` against the dockerized engine (`engine-harness`, e.g. `triage/TriageAggregationIT`) | assertion against real engine state |
| Frontend logic and components | Vitest + Testing Library in `frontend/src/**` (e.g. `src/actions/DestructiveModal.test.tsx`) | assertion |
| A user flow across BFF + SPA | Playwright in `frontend/e2e/` | the step's `expect` fails |
| Bash script / CI script | a new `scripts/<x>-selftest.sh` with fixture cases first, the real tree last | `N passed, 1 failed` on the planted case |

An e2e spec or a rung-4 IT is never the FIRST red when a lower rung can state the rule — it proves integration
of a change the lower red already specified.

Iron rules that apply to every rung (CLAUDE.md):

- `Thread.sleep()` in any test is a hard failure (`NoSleepInTestsArchTest`); time-dependent asserts use
  Awaitility with explicit bounds against real engine/BFF state.
- Never mock Flowable responses for join logic — the DLQ/suspended join is proven on rung 4 only.
- Never insert rows into engine (`ACT_*`) tables, not even in tests — seed over REST (`EngineSeed`).

## Before the pull request — the combined RED-set run

GREEN proves each test alone; the pull request must prove them together. Before opening it, run every test
class and selftest that any `test(#N): RED` commit in `origin/main..HEAD` touched, as ONE run on the tip you
will push:

```bash
CLASSES=$(git log --format=%H origin/main..HEAD --grep='^test(#' \
  | xargs -I{} git diff-tree --no-commit-id --name-only -r {} -- backend/src/test/java \
  | grep -oE '[A-Za-z0-9_]+Test\.java$' | sed 's/\.java$//' | sort -u | paste -sd,)
[ -n "$CLASSES" ] && (cd backend && mvn -o -q test -Dtest="$CLASSES,*ArchTest" -Dsurefire.failIfNoSpecifiedTests=false)
# plus: cd frontend && npx vitest run <each touched *.test.ts(x)>; bash <each touched selftest>
```

A red there is a cross-change interaction (shared fixture, test order, a context key) and is fixed before a CI
minute is spent. Then `scripts/ci-local.sh` and the `green-ci` loop.

## Enforced vs doctrine (be honest about which is which)

- **Enforced on `main` today:** `NoSleepInTestsArchTest`; Spotless and ESLint in CI; the PR lane (`lint`,
  `unit`, `frontend`, `e2e`) green on the head before the `queue` label; the full light + heavy run on every
  merge-queue batch.
- **Arriving with #640 (not live yet):** the RED commit before the code commit (`lr-1`), the frozen-RED check
  (`lr-2`), the assertion-weakening check (`lr-3`), the human-only waiver signer (`lr-5`), and the repo hooks.
  Until #640 lands they are doctrine; follow them anyway — see `docs/landing-rules.md`.
- **Doctrine only:** that the RED test actually FAILED for the right reason (the proof line is yours to quote
  honestly), the `RED:` sha line, the four questions above. A pull request whose `backend/src/main` or
  `frontend/src` diff has no test counterpart is sent back in review.

## Anti-patterns this skill exists to stop

- Writing the implementation, then a test that mirrors it line by line (tautology — it pins nothing).
- "RED" that is a compile error against a class that does not exist yet.
- A test that passes on the first run — it did not fail, so it is not RED; plant a mutant or fix the test.
- Boundary tests on the wrong side: the test pins `100` as valid but the spec said the range ends at `99`.
- A `@SpringBootTest` for pure logic because the context was already there (wrong rung — `unit-test-patterns`).
- Hand-written engine JSON standing in for the real wire shape of the status join (wrong rung — rung 4).
- Asserting the full exception message string, then every wording fix breaks the test.
- Editing the RED test in the GREEN commit until it passes (`lr-2`).
