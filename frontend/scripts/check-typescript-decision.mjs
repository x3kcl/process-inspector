#!/usr/bin/env node
// Gate for #458: typescript is either major 7, or an explicit 5.9.x hold.
// Any other major (including 6, and 5.9.x with no ts7-hold marker) fails.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const pkgPath = fileURLToPath(new URL('../package.json', import.meta.url))
const lines = readFileSync(pkgPath, 'utf8').split('\n')
const idx = lines.findIndex((line) => /"typescript"\s*:/.test(line))
if (idx < 0) {
  console.error('typescript decision FAILED: no "typescript" pin in package.json')
  process.exit(1)
}

const line = lines[idx]
const prev = idx > 0 ? lines[idx - 1] : ''
const match = line.match(/"typescript"\s*:\s*"([^"]+)"/)
if (!match) {
  console.error('typescript decision FAILED: unparseable typescript pin')
  console.error(line)
  process.exit(1)
}

const spec = match[1]
const version = spec.replace(/^[^\d]*/, '')
const major = Number(version.split('.')[0])
const is59 = /^5\.9\.\d+/.test(version)
const hold = line.includes('ts7-hold:') || prev.includes('ts7-hold:')

if (major === 7) {
  console.log(`typescript ${spec}: major 7`)
  process.exit(0)
}
if (is59 && hold) {
  console.log(`typescript ${spec}: 5.9.x with ts7-hold`)
  process.exit(0)
}

console.error(
  `typescript decision FAILED: ${spec} (need major 7, or 5.9.x with ts7-hold: on this line or the one above)`,
)
process.exit(1)
