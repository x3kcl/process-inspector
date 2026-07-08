#!/usr/bin/env node
// Build gate for R-GOV-05: the bpmn.io license requires the "Powered by bpmn.io"
// watermark to remain visible and unmodified (https://bpmn.io/license/). The same
// term applies to every bpmn.io toolkit we embed — bpmn-js emits `.bjs-powered-by`
// and cmmn-js (Stage-2 CMMN case detail, Phase 2) emits `.cmmn-powered-by`.
// Application code never has a legitimate reason to reference either watermark
// element, so any mention of its class is treated as an attempt to hide, restyle,
// or remove it and fails the build.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const TARGETS = ['src', 'index.html']
// Matches both the bpmn-js (`bjs-powered-by`) and cmmn-js (`cmmn-powered-by`)
// watermark classes — neither may be selected, restyled, hidden, or removed.
const FORBIDDEN = /(bjs|cmmn)-powered-by/i

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
  console.error('bpmn.io watermark guard FAILED (R-GOV-05).')
  console.error('The "Powered by bpmn.io" watermark must stay visible and unmodified')
  console.error('(https://bpmn.io/license/) — do not select, hide, or remove the')
  console.error('`.bjs-powered-by` (bpmn-js) or `.cmmn-powered-by` (cmmn-js) element.')
  for (const hit of hits) console.error(`  ${hit}`)
  process.exit(1)
}

console.log('bpmn.io watermark guard OK - no code touches the watermark element.')
