# Landing rules

Read before your first commit. These rules keep a test you wrote from being bent to fit the code. The doctrine
behind them is the `tdd` skill (`.claude/skills/tdd/SKILL.md`).

**Status:** the merge-queue checks for these rules arrive with #640. Until then they are doctrine, checked in
review; the anchors below are what a gate refusal will link.

Paths the rules read:

- **Test paths:** `backend/src/test/`, `frontend/e2e/`, `frontend/src/*.test.ts`, `frontend/src/*.test.tsx`,
  `scripts/*selftest.sh`
- **Code paths:** `backend/src/main/`, `frontend/src/`, `scripts/`, `ci/`

A test path wins over a code path, so `frontend/src/x.test.ts` and `scripts/foo-selftest.sh` are tests, not code.

<a id="lr-1"></a>
## LR-1 red-first

**What** A commit under a code path lands only after an earlier commit of the same pull request touched a test
path, with `RED` in its subject (`test(#N): RED — …`).

**Why** A test written after the code can only describe what the code already does; the failing run is the
proof the test asks something.

**Comply up front** Commit 1 holds the test (and signature stubs that throw) with subject
`test(#N): RED — <what>`; run it and keep the first failure line for the pull-request body (`RED:` sha line +
`RED proof:` line). Code follows in later commits. A commit with nothing to fail — move-only refactor,
generated code (`frontend/src/api/schema.d.ts`), dependency bump, docs or config — carries a
`No-RED: <reason>` trailer in its body.

**If you must change it** There is no waiver line. Add the RED commit before the code commit, or write the
`No-RED:` trailer; the reason is read by the reviewer.

<a id="lr-2"></a>
## LR-2 red-frozen

**What** Every path a `test(#N): RED` commit touched under a test path is byte-identical at the pull-request
head. A later RED commit on the same file only appends: the earlier RED commit's lines stay a contiguous line
prefix.

**Why** A RED test the implementer may edit later can be rewritten until it passes, and then proves nothing.

**Comply up front** Keep code under test out of the RED file: a helper the GREEN commit must change lives in a
file the RED commit does not touch (for a selftest, a helper script it sources). If the RED commit left a
test wrong, do not patch it: it is a contract change.

**If you must change it** A line in the pull-request body, one per path, signed by a human reviewer (LR-5):
`Contract-change: <path> — <what changed and why> — reviewed-by <NAME>`

<a id="lr-3"></a>
## LR-3 assertion-weaken

**What** A test hunk that removes strong assertions (`isEqualTo`, `containsExactly`, `hasSize`, `isInstanceOf`,
`hasMessageContaining`, `assertThatThrownBy`, `toEqual`, `toHaveBeenCalledWith`, …) must add at least as many
strong assertions in the same hunk.

**Why** Swapping `isEqualTo(x)` for `isNotNull()` (or `toEqual(x)` for `toBeDefined()`) turns a failing test
green without fixing anything.

**Comply up front** Keep the exact-value matcher count in each hunk. If the expected value changed, change the
value inside a matcher of the same strength, in a RED commit of its own.

**If you must change it** `Contract-change: <path> — <what changed and why> — reviewed-by <NAME>`, one line per
test file.

<a id="lr-5"></a>
## LR-5 who signs a waiver

**What** `reviewed-by NAME` in a `Contract-change:` line names a human who did not write the change.

**Why** A waiver signed by its own author is no second look; the rule then has no check left.

**Comply up front** Ask the human reviewer, say what changed, and put their name on the line only once they
agreed. When no human is at hand, do not waive: restructure under LR-2 or LR-3 instead.

**If you must change it** There is no waiver for the signer. An agent (claude, orchestrator, any bot) never
signs, whatever its role in the work.
