#!/usr/bin/env node
// Build gate for ADR-002 / R-GOV-05: the grid is AG Grid COMMUNITY only. Enterprise
// features (status bar, set filters, range selection, context menu) must not creep in —
// any reference to the enterprise packages fails the build.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const TARGETS = ['src', 'package.json']
const FORBIDDEN = /ag-grid-enterprise|@ag-grid-enterprise\//i

const hits = []

function scan(path) {
  if (statSync(path).isDirectory()) {
    for (const entry of readdirSync(path)) scan(join(path, entry))
    return
  }
  readFileSync(path, 'utf8')
    .split('\n')
    .forEach((line, i) => {
      if (FORBIDDEN.test(line)) {
        hits.push(`${relative(ROOT, path)}:${i + 1}: ${line.trim()}`)
      }
    })
}

for (const target of TARGETS) {
  scan(join(ROOT, target))
}

if (hits.length > 0) {
  console.error('AG Grid Community guard FAILED (ADR-002).')
  console.error('Enterprise packages are banned — Community only.')
  for (const hit of hits) console.error(`  ${hit}`)
  process.exit(1)
}

console.log('AG Grid Community guard OK - no enterprise imports.')
