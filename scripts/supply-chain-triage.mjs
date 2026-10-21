#!/usr/bin/env node
//
// supply-chain-triage.mjs — one open issue for a red nightly supply-chain job (#447).
//
// Reads Trivy JSON (trivy-*.json) and the npm-audit sidecar (npm-audit.json, written by
// scripts/check-npm-audit-allowlist.mjs when SUPPLY_CHAIN_NPM_REPORT is set). Prints a
// short table. With --file-issue, upserts a single GitHub issue and closes it on a later
// green run.
//
// The body carries the marker `supply-chain-triage`. Listing is by the `supply-chain`
// label plus that marker, so other supply-chain issues (Dependabot, epics) stay put.
//
//   node scripts/supply-chain-triage.mjs --self-check
//   node scripts/supply-chain-triage.mjs --dir supply-chain-reports
//   node scripts/supply-chain-triage.mjs --dir supply-chain-reports --file-issue
//   node scripts/supply-chain-triage.mjs --drill red|green --file-issue
//
// --file-issue needs GH_TOKEN (or GITHUB_TOKEN) and GITHUB_REPOSITORY=owner/repo.
// RUN_URL is the Actions run linked from the issue.
// SCAN_FAILED=true means a build or scan step failed. A green close requires that
// unset (or false) and zero parsed findings — a DB-download failure must not close
// the tracker just because no JSON was written.

import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const MARKER = 'supply-chain-triage'
const TITLE = '[supply-chain] Nightly advisory triage'

function parseArgs(argv) {
  const opts = { dir: 'supply-chain-reports', fileIssue: false, drill: null, selfCheck: false }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--dir') opts.dir = argv[++i]
    else if (a === '--file-issue') opts.fileIssue = true
    else if (a === '--drill') opts.drill = argv[++i]
    else if (a === '--self-check') opts.selfCheck = true
    else {
      console.error(`unknown argument: ${a}`)
      process.exit(2)
    }
  }
  return opts
}

export function findingsFromTrivy(json) {
  const out = []
  for (const result of json.Results ?? []) {
    for (const v of result.Vulnerabilities ?? []) {
      out.push({
        source: 'trivy',
        id: v.VulnerabilityID,
        package: v.PkgName ?? '',
        installed: v.InstalledVersion ?? '',
        fixed: v.FixedVersion ?? '',
        severity: v.Severity ?? '',
        detail: result.Target ?? '',
      })
    }
  }
  return out
}

export function findingsFromNpm(json) {
  const out = []
  for (const a of json.unallowlisted ?? []) {
    out.push({
      source: 'npm-audit',
      id: a.ghsaId,
      package: a.package ?? '',
      installed: '',
      fixed: '',
      severity: a.severity ?? 'high',
      detail: a.title ?? a.url ?? '',
    })
  }
  for (const e of json.expired ?? []) {
    out.push({
      source: 'npm-audit',
      id: e.ghsaId,
      package: '',
      installed: '',
      fixed: '',
      severity: 'expired-allowlist',
      detail: `allowlist entry expired ${e.expires}`,
    })
  }
  for (const line of json.drift ?? []) {
    out.push({
      source: 'npm-audit',
      id: 'trivyignore-drift',
      package: '',
      installed: '',
      fixed: '',
      severity: 'drift',
      detail: line,
    })
  }
  return out
}

export function loadFindings(dir) {
  if (!existsSync(dir)) return []
  const findings = []
  for (const name of readdirSync(dir)) {
    if (!name.endsWith('.json')) continue
    const json = JSON.parse(readFileSync(resolve(dir, name), 'utf8'))
    if (name.startsWith('trivy-')) findings.push(...findingsFromTrivy(json))
    else if (name === 'npm-audit.json') findings.push(...findingsFromNpm(json))
  }
  return findings
}

// openIssues are already this tracker only. scanFailed covers a step that died
// before writing a report — that is still red, and must not close the issue.
export function plan(openIssues, findings, scanFailed) {
  const existing = openIssues[0] ?? null
  const red = findings.length > 0 || scanFailed
  if (!red) return existing ? { action: 'close', number: existing.number } : { action: 'noop' }
  return existing ? { action: 'update', number: existing.number } : { action: 'open' }
}

export function renderBody({ findings, scanFailed, runUrl }) {
  const lines = [
    MARKER,
    '',
    'Opened by the nightly `supply-chain` job (#447). One issue for the current red streak; a later green run closes it. Not an auto-fix.',
    '',
  ]
  if (runUrl) lines.push(`Run: ${runUrl}`, '')
  if (scanFailed && findings.length === 0) {
    lines.push('A scan or image build failed before an advisory report was written. Read the failing step in the run log.', '')
  }
  if (findings.length > 0) {
    lines.push(
      '| source | id | package | installed | fixed | severity | detail |',
      '| --- | --- | --- | --- | --- | --- | --- |',
    )
    for (const f of findings) {
      const row = [f.source, f.id, f.package, f.installed, f.fixed, f.severity, f.detail].map(cell)
      lines.push(`| ${row.join(' | ')} |`)
    }
    lines.push('')
  }
  return lines.join('\n')
}

function cell(value) {
  return String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ')
}

function printTable(findings, scanFailed) {
  if (findings.length === 0 && !scanFailed) {
    console.log('supply-chain-triage: no advisories in the parsed reports.')
    return
  }
  console.log(`supply-chain-triage: ${findings.length} advisory row(s)${scanFailed ? '; a scan step failed' : ''}.`)
  for (const f of findings) {
    console.log(
      `  [${f.source}] ${f.severity} ${f.id} ${f.package} installed=${f.installed || '-'} fixed=${f.fixed || '-'} ${f.detail}`,
    )
  }
}

async function gh(method, path, body) {
  const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN
  const repo = process.env.GITHUB_REPOSITORY
  if (!token || !repo) {
    console.error('supply-chain-triage: GH_TOKEN and GITHUB_REPOSITORY are required for --file-issue')
    process.exit(2)
  }
  const res = await fetch(`https://api.github.com${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'supply-chain-triage',
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    const text = await res.text()
    console.error(`supply-chain-triage: GitHub ${method} ${path} → ${res.status} ${text.slice(0, 400)}`)
    process.exit(2)
  }
  if (res.status === 204) return null
  return res.json()
}

async function listOpenTrackers() {
  const repo = process.env.GITHUB_REPOSITORY
  const data = await gh('GET', `/repos/${repo}/issues?labels=supply-chain&state=open&per_page=100`)
  return data
    .filter((item) => !item.pull_request && (item.body ?? '').includes(MARKER))
    .map((item) => ({ number: item.number }))
    .sort((a, b) => a.number - b.number)
}

async function applyPlan(decision, body) {
  const repo = process.env.GITHUB_REPOSITORY
  if (decision.action === 'noop') {
    console.log('supply-chain-triage: green, no open tracker.')
    return
  }
  if (decision.action === 'open') {
    const created = await gh('POST', `/repos/${repo}/issues`, {
      title: TITLE,
      body,
      labels: ['supply-chain'],
    })
    console.log(`supply-chain-triage: opened #${created.number}`)
    return
  }
  if (decision.action === 'update') {
    await gh('PATCH', `/repos/${repo}/issues/${decision.number}`, { title: TITLE, body })
    const run = process.env.RUN_URL ? ` ${process.env.RUN_URL}` : ''
    await gh('POST', `/repos/${repo}/issues/${decision.number}/comments`, { body: `Still red.${run}` })
    console.log(`supply-chain-triage: updated #${decision.number}`)
    return
  }
  const run = process.env.RUN_URL ?? 'a later green run'
  await gh('POST', `/repos/${repo}/issues/${decision.number}/comments`, {
    body: `Supply-chain job is green. Closing. ${run}`,
  })
  await gh('PATCH', `/repos/${repo}/issues/${decision.number}`, { state: 'closed', state_reason: 'completed' })
  console.log(`supply-chain-triage: closed #${decision.number}`)
}

function drillFindings(drill) {
  if (drill === 'green') return []
  if (drill === 'red') {
    return [
      {
        source: 'drill',
        id: 'DRILL-0000',
        package: 'supply-chain-triage',
        installed: '0',
        fixed: 'n/a',
        severity: 'HIGH',
        detail: 'synthetic finding from workflow_dispatch triage-drill=red; not a real advisory',
      },
    ]
  }
  console.error(`supply-chain-triage: --drill must be red or green, got ${drill}`)
  process.exit(2)
}

function selfCheck() {
  const trivy = findingsFromTrivy({
    Results: [
      {
        Target: 'pom.xml',
        Vulnerabilities: [
          {
            VulnerabilityID: 'CVE-2026-0001',
            PkgName: 'example',
            InstalledVersion: '1.0',
            FixedVersion: '1.1',
            Severity: 'HIGH',
          },
        ],
      },
    ],
  })
  if (trivy.length !== 1 || trivy[0].id !== 'CVE-2026-0001' || trivy[0].fixed !== '1.1') throw new Error('trivy parse')
  if (findingsFromTrivy({ Results: [{}] }).length !== 0) throw new Error('empty trivy')
  const npm = findingsFromNpm({
    unallowlisted: [{ ghsaId: 'GHSA-xxxx-yyyy-zzzz', severity: 'high', title: 'demo' }],
    expired: [{ ghsaId: 'GHSA-aaaa-bbbb-cccc', expires: '2020-01-01' }],
    drift: ['id mismatch'],
  })
  if (npm.length !== 3) throw new Error('npm parse')
  if (plan([], [], false).action !== 'noop') throw new Error('green noop')
  if (plan([{ number: 7 }], [], false).action !== 'close') throw new Error('green close')
  if (plan([], trivy, false).action !== 'open') throw new Error('red open')
  if (plan([{ number: 7 }], [], true).action !== 'update') throw new Error('failed step updates')
  const runUrl = 'run-marker-1'
  const body = renderBody({ findings: trivy, scanFailed: false, runUrl })
  if (!body.includes(MARKER) || !body.includes('CVE-2026-0001') || !body.includes(runUrl)) {
    throw new Error('body')
  }
  if (plan([{ number: 3 }, { number: 9 }], trivy, false).number !== 3) throw new Error('oldest tracker')
  console.log('supply-chain-triage: self-check passed')
}

async function main() {
  const opts = parseArgs(process.argv.slice(2))
  if (opts.selfCheck) {
    selfCheck()
    return
  }
  const scanFailed = opts.drill === 'red' ? true : process.env.SCAN_FAILED === 'true'
  const findings = opts.drill ? drillFindings(opts.drill) : loadFindings(resolve(opts.dir))
  printTable(findings, scanFailed && findings.length === 0)
  if (!opts.fileIssue) return
  const open = await listOpenTrackers()
  const decision = plan(open, findings, opts.drill === 'green' ? false : scanFailed)
  const body = renderBody({
    findings,
    scanFailed: opts.drill === 'green' ? false : scanFailed && findings.length === 0,
    runUrl: process.env.RUN_URL ?? '',
  })
  await applyPlan(decision, body)
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main().catch((err) => {
    console.error(err)
    process.exit(2)
  })
}
