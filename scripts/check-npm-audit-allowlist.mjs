#!/usr/bin/env node
//
// check-npm-audit-allowlist.mjs — the HONEST replacement for a bare
// `npm audit --audit-level=high` gate (issue #297).
//
// The bare gate goes permanently red the moment ANY high+ advisory lacks an in-range
// fix (react-router's RSC-only CVE, the @redocly/openapi-core -> minimatch ->
// brace-expansion chain that stalled at a major boundary) — and a permanently-red gate
// trains the team to ignore nightly triage, which is worse than no gate at all.
//
// This script runs `npm audit --json` against frontend/package-lock.json (via `npm ci`'s
// lockfile — same determinism the redocly-overrides lesson relies on) and diffs the
// high/critical advisories it finds against a checked-in allowlist
// (frontend/npm-audit-allowlist.json). It fails the run on either:
//   1. an UN-ALLOWLISTED advisory — a high/critical GHSA id with no matching entry, or
//   2. an EXPIRED entry — an allowlist entry whose `expires` date has passed, even if the
//      advisory is still unfixed. This is the anti-rot mechanism: an exception can't just
//      sit there forever silently "covering" a gate. Someone has to look again, re-check
//      for a fresh in-range fix (see .claude/skills memory: npm-audit-redocly-overrides —
//      "no fix available" is usually wrong the first time; confirm it's STILL wrong before
//      renewing), and either renew with a new date + justification or escalate.
//
// A patchable advisory (like postcss's GHSA-r28c-9q8g-f849) is never allowlisted — it's
// just fixed (`npm audit fix`) and drops out of the audit output entirely. The allowlist is
// ONLY for advisories confirmed to have no safe in-range fix at review time.
//
// Usage (from repo root or anywhere):
//   node scripts/check-npm-audit-allowlist.mjs
//   node scripts/check-npm-audit-allowlist.mjs --dir frontend --allowlist frontend/npm-audit-allowlist.json
//
// Exit 0 = every current high/critical advisory is covered by a live (unexpired) allowlist
//          entry, and no allowlist entry has expired.
// Exit 1 = an un-allowlisted advisory and/or an expired entry was found (report printed).
// Exit 2 = the script itself couldn't run `npm audit` / parse its output / read the
//          allowlist (a tooling failure, not an audit finding).

import { execFileSync } from 'node:child_process'
import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HIGH_SEVERITIES = new Set(['high', 'critical'])
const GHSA_RE = /GHSA-[a-z0-9]+-[a-z0-9]+-[a-z0-9]+/i

function parseArgs(argv) {
  const opts = { dir: 'frontend', allowlist: null }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--dir') opts.dir = argv[++i]
    else if (a === '--allowlist') opts.allowlist = argv[++i]
    else {
      console.error(`unknown argument: ${a}`)
      process.exit(2)
    }
  }
  return opts
}

function repoRoot() {
  // scripts/ lives at the repo root's top level.
  return resolve(dirname(fileURLToPath(import.meta.url)), '..')
}

function runNpmAudit(cwd) {
  // npm audit exits non-zero the moment it finds ANY vulnerability (even low), so we
  // can't gate on the exit code — read stdout regardless and parse it. A genuine tooling
  // failure (npm itself missing, no node_modules, network dead for the advisory DB) produces
  // stdout that isn't valid JSON, which the caller treats as a hard tooling error (exit 2).
  let stdout
  try {
    stdout = execFileSync('npm', ['audit', '--json'], { cwd, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  } catch (err) {
    // execFileSync throws on non-zero exit; npm still writes the JSON report to stdout.
    stdout = err.stdout ?? ''
  }
  try {
    return JSON.parse(stdout)
  } catch {
    console.error('check-npm-audit-allowlist: `npm audit --json` did not return parseable JSON.')
    console.error('--- raw output ---')
    console.error(stdout)
    process.exit(2)
  }
}

// Collects one entry per distinct GHSA advisory at high/critical severity. npm audit's
// `vulnerabilities` map contains both real advisories (a `via` entry that's an OBJECT with a
// `url`/`source`) and pure dependency-chain wrappers (a `via` entry that's just a STRING
// naming another vulnerable package) — only the former are actual advisories to allowlist.
function collectHighSeverityAdvisories(auditJson) {
  const byGhsaId = new Map()
  for (const pkgReport of Object.values(auditJson.vulnerabilities ?? {})) {
    for (const via of pkgReport.via ?? []) {
      if (typeof via === 'string') continue // a wrapper reference to another package, not an advisory
      const severity = via.severity ?? pkgReport.severity
      if (!HIGH_SEVERITIES.has(severity)) continue
      const match = GHSA_RE.exec(via.url ?? '')
      if (!match) continue
      const displayId = match[0] // preserve the standard lowercase-body casing for display
      const key = displayId.toUpperCase() // case-insensitive matching against the allowlist
      if (!byGhsaId.has(key)) {
        byGhsaId.set(key, { ghsaId: displayId, severity, title: via.title ?? '(no title)', url: via.url })
      }
    }
  }
  return byGhsaId
}

function loadAllowlist(path) {
  if (!existsSync(path)) {
    console.error(`check-npm-audit-allowlist: allowlist file not found: ${path}`)
    process.exit(2)
  }
  let parsed
  try {
    parsed = JSON.parse(readFileSync(path, 'utf8'))
  } catch (err) {
    console.error(`check-npm-audit-allowlist: allowlist file is not valid JSON: ${path}`)
    console.error(String(err))
    process.exit(2)
  }
  const entries = parsed.entries ?? []
  for (const entry of entries) {
    if (!entry.ghsaId || !GHSA_RE.test(entry.ghsaId)) {
      console.error(`check-npm-audit-allowlist: allowlist entry missing/invalid ghsaId: ${JSON.stringify(entry)}`)
      process.exit(2)
    }
    if (!entry.expires || Number.isNaN(Date.parse(entry.expires))) {
      console.error(`check-npm-audit-allowlist: allowlist entry for ${entry.ghsaId} has no valid ISO 'expires' date`)
      process.exit(2)
    }
    if (!entry.justification || entry.justification.trim().length < 20) {
      console.error(`check-npm-audit-allowlist: allowlist entry for ${entry.ghsaId} needs a real justification (>=20 chars)`)
      process.exit(2)
    }
  }
  return entries
}

function daysUntil(isoDate, now) {
  const ms = Date.parse(`${isoDate}T00:00:00Z`) - now.getTime()
  return Math.floor(ms / (24 * 60 * 60 * 1000))
}

function main() {
  const opts = parseArgs(process.argv.slice(2))
  const root = repoRoot()
  const auditDir = resolve(root, opts.dir)
  const allowlistPath = resolve(root, opts.allowlist ?? `${opts.dir}/npm-audit-allowlist.json`)

  const auditJson = runNpmAudit(auditDir)
  const advisories = collectHighSeverityAdvisories(auditJson)
  const allowlist = loadAllowlist(allowlistPath)
  const allowlistByGhsaId = new Map(allowlist.map((e) => [e.ghsaId.toUpperCase(), e]))

  const now = new Date()
  const expired = []
  const unallowlisted = []
  const covered = []

  // 1. Every allowlist entry's expiry is checked unconditionally — an exception that has
  //    aged out fails the gate even if the underlying advisory somehow disappeared, so a
  //    stale entry always forces a human decision (renew or delete) rather than rotting.
  for (const entry of allowlist) {
    const remaining = daysUntil(entry.expires, now)
    if (remaining < 0) {
      expired.push({ ...entry, remaining })
    }
  }

  // 2. Every current high/critical advisory must be covered by a live (unexpired) entry.
  for (const advisory of advisories.values()) {
    const entry = allowlistByGhsaId.get(advisory.ghsaId.toUpperCase())
    const isExpired = entry && daysUntil(entry.expires, now) < 0
    if (!entry || isExpired) {
      unallowlisted.push(advisory)
    } else {
      covered.push({ advisory, entry })
    }
  }

  console.log(`check-npm-audit-allowlist: ${advisories.size} high/critical advisor${advisories.size === 1 ? 'y' : 'ies'} found in ${opts.dir}`)
  for (const { advisory, entry } of covered) {
    console.log(`  [allowlisted, ${daysUntil(entry.expires, now)}d remaining] ${advisory.ghsaId} — ${advisory.title}`)
  }
  for (const advisory of unallowlisted) {
    const entry = allowlistByGhsaId.get(advisory.ghsaId.toUpperCase())
    if (entry) {
      console.log(`  [EXPIRED ENTRY, was covering] ${advisory.ghsaId} — ${advisory.title} (expired ${entry.expires})`)
    } else {
      console.log(`  [UN-ALLOWLISTED] ${advisory.ghsaId} — ${advisory.title} (${advisory.url})`)
    }
  }
  for (const entry of expired) {
    if (!advisories.has(entry.ghsaId.toUpperCase())) {
      console.log(`  [EXPIRED ENTRY, advisory no longer in audit output — remove or verify] ${entry.ghsaId} (expired ${entry.expires})`)
    }
  }

  if (unallowlisted.length > 0 || expired.length > 0) {
    console.error('')
    console.error(
      `check-npm-audit-allowlist: FAILED — ${unallowlisted.length} un-allowlisted/expired-coverage advisor${unallowlisted.length === 1 ? 'y' : 'ies'}, ${expired.length} expired allowlist entr${expired.length === 1 ? 'y' : 'ies'}.`,
    )
    console.error(
      'Fix a patchable advisory with `npm audit fix` (postcss-style); for one with no in-range fix, add/renew a dated entry in',
      allowlistPath.replace(`${root}/`, ''),
      'with a fresh justification and expiry — see scripts/check-npm-audit-allowlist.mjs header comment.',
    )
    process.exit(1)
  }

  console.log('')
  console.log('check-npm-audit-allowlist: PASSED — no un-allowlisted high/critical advisories, no expired entries.')
}

main()
