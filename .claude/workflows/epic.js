// .claude/workflows/epic.js — implement an epic's child issues in parallel (first run: #639/#640, 2026-11-14).
// Invoke: Workflow({name: 'epic', args: {children: [{issue, branch, model, extra, reviewExtra?}, ...]}})
//   issue  the child issue number (the issue body is the spec and names its Write-set)
//   branch the branch to create from origin/main; the worktree is ~/workspace/pi-wt-<issue>
//   model  sonnet | opus | fable (the issue's model:* label); the reviewer is always opus, with fresh context
//   extra  per-child notes: anchors for shared files, reference material, what not to run
// The run stops at "PR lane green + reviewed". Landing is the orchestrator's step: scripts/pr-queue.sh <PR> --watch.
// Children must have disjoint write-sets; trial-merge the branches before queueing.
export const meta = {
  name: 'epic',
  description: 'Implement the children of an epic in parallel: worktree per child, RED then GREEN, PR, CI green, independent review + fix',
  phases: [
    { title: 'Implement', detail: 'one worktree + one PR per child issue' },
    { title: 'Review', detail: 'fresh reviewer reads the diff, fixes findings, re-greens CI' },
  ],
}

const CHILDREN = (typeof args === 'string' ? JSON.parse(args) : args).children

const COMMON = `
Repository: x3kcl/process-inspector, main checkout /home/flapci/workspace/process-inspector (it sits on an UNRELATED stale branch — never work in it, never switch its branch).
Ground rules:
- Work only in your own worktree, created from the latest origin/main.
- The authoritative project instructions are \`git show origin/main:CLAUDE.md\` (the injected CLAUDE.md is from a stale branch — read the origin/main one first, especially "Merge flow (ci-lib queue)").
- flap reference material: /home/flapci/workspace/flap (default branch master; read with plain file reads). ci-lib: /home/flapci/workspace/ci-lib (use \`git -C ... show v0.17:<path>\`; the checkout itself is stale).
- Stay inside the issue's Write-set. Other agents are editing other files of the same repo in parallel; in ci/contract.sh touch ONLY what your issue names, at the exact anchor given below, so the pull requests merge without conflict.
- Commits: test-first where the issue says so — commit 1 \`test(#N): RED — <what it pins>\` (must fail on an assertion when run), then the implementation. Docs-only commits carry a \`No-RED: docs only\` trailer. End every commit message with the line: Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com> . No model names anywhere else.
- GitHub: no gh CLI. Use the github MCP tools (load via ToolSearch, e.g. "select:mcp__github__create_pull_request") or curl with $GITHUB_PERSONAL_ACCESS_TOKEN (never echo it). Before opening the PR run \`scripts/ci-runner.sh ensure\`.
- PR body: what/why, "Closes #N", a \`RED:\` line (sha) and \`RED proof:\` line (the failing output before the fix) when the issue is test-first, and end with: 🤖 Generated with [Claude Code](https://claude.com/claude-code)
- After pushing, block on \`scripts/ci-watch.sh <head-sha>\` (PR lane = lint, unit, frontend, e2e) and fix reds in your branch until green. A red that is infrastructure (docker socket, runner slot) may be re-run once via the Actions API; say so.
- NEVER: merge, add the \`queue\` label, push to main, deploy, touch the demo, edit .claude/settings*.json permissions, or ask another model to review.
- Shell scripts must pass \`./scripts/shell-syntax-check.sh\`. Keep it minimal: the least code that satisfies the issue.
`

const RESULT = {
  type: 'object',
  properties: {
    pr: { type: 'integer' },
    branch: { type: 'string' },
    worktree: { type: 'string' },
    headSha: { type: 'string' },
    ciGreen: { type: 'boolean' },
    summary: { type: 'string' },
    openPoints: { type: 'array', items: { type: 'string' } },
  },
  required: ['pr', 'branch', 'worktree', 'headSha', 'ciGreen', 'summary', 'openPoints'],
}

const REVIEW = {
  type: 'object',
  properties: {
    pr: { type: 'integer' },
    headSha: { type: 'string' },
    ciGreen: { type: 'boolean' },
    verdict: { type: 'string', enum: ['ready', 'not-ready'] },
    findingsFixed: { type: 'array', items: { type: 'string' } },
    findingsOpen: { type: 'array', items: { type: 'string' } },
  },
  required: ['pr', 'headSha', 'ciGreen', 'verdict', 'findingsFixed', 'findingsOpen'],
}

const results = await pipeline(
  CHILDREN,
  c => agent(
    `Implement GitHub issue #${c.issue} of x3kcl/process-inspector end to end and open a pull request.
Read the issue first (github MCP issue_read, or curl) — it is the spec, including its Write-set.
Worktree: \`git -C /home/flapci/workspace/process-inspector fetch origin main && git -C /home/flapci/workspace/process-inspector worktree add /home/flapci/workspace/pi-wt-${c.issue} -b ${c.branch} origin/main\`.
${c.extra}
${COMMON}
Return the PR number, branch, worktree path, head sha, whether the PR-lane CI is green on that sha, a short summary, and open points.`,
    { label: `impl #${c.issue}`, phase: 'Implement', model: c.model, schema: RESULT }),
  (r, c) => r && agent(
    `You are an independent reviewer with fresh context. Review pull request #${r.pr} (branch ${r.branch}, worktree ${r.worktree}) of x3kcl/process-inspector against its issue #${c.issue} (read the issue: it is the spec).
Read the diff with \`git -C ${r.worktree} diff origin/main...HEAD\` (not the MCP get_files call — it is huge).
Check, by running things, not by reading alone:
- every acceptance point and rule of the issue; the Write-set was respected;
- the RED commit exists where required and really fails at its sha on an assertion (check it out in a scratch worktree and run it), and passes at the tip;
- every path, command, script, class, workflow key the change cites exists and works (grep / run them); docs claim nothing that is not true on this branch;
- selftests are run by the lint part; \`bash ci/contract.sh ci-light\` with CI_PART=lint passes in the worktree;
- shell: quoting, set -euo pipefail traps, token never echoed; workflow YAML: job ids/names unchanged where the issue demands it.
${c.reviewExtra || ''}
Fix every real finding yourself in the same worktree (new commits, same commit rules), push, and block on \`scripts/ci-watch.sh <head-sha>\` until the PR lane is green on the final head. Comment a short review summary on the PR.
${COMMON}
Return the final head sha, CI state, verdict (ready only if green and no open finding), what you fixed, what is still open.`,
    { label: `review #${c.issue}`, phase: 'Review', model: 'opus', schema: REVIEW })
      .then(v => ({ issue: c.issue, impl: r, review: v })),
)

return results.filter(Boolean)
