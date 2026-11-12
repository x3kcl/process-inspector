#!/usr/bin/env node
// CI gate for R4 (TEST-STRATEGY §1: "axe accessibility checks hard-fail"). scanA11y() is an
// explicit per-spec call (e2e/a11y.ts), not an autouse fixture, so nothing stops a new spec
// from shipping with zero axe coverage — this script closes that gap by failing the build if
// any e2e/*.spec.ts or e2e-real/*.spec.ts file never calls scanA11y().
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
// e2e/ = hermetic (page.route) specs; e2e-real/ = the real-stack nightly smoke (#466).
const specs = ['e2e', 'e2e-real'].flatMap((dir) =>
  readdirSync(join(ROOT, dir))
    .filter((f) => f.endsWith('.spec.ts'))
    .map((f) => `${dir}/${f}`),
)
const missing = specs.filter((f) => !readFileSync(join(ROOT, f), 'utf8').includes('scanA11y('))

if (missing.length > 0) {
  console.error('e2e a11y coverage guard FAILED (R4 — axe checks hard-fail).')
  console.error(
    'Every e2e/*.spec.ts and e2e-real/*.spec.ts file must call scanA11y() at least once:',
  )
  for (const file of missing) console.error(`  ${file}`)
  process.exit(1)
}

console.log(`e2e a11y coverage guard OK - scanA11y() present in all ${specs.length} spec(s).`)
