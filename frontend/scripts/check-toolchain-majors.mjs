#!/usr/bin/env node
// CI gate for #457: the frontend toolchain majors move together. A partial bump
// (vite without vitest, or the reverse) fails this check before lint.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const pkg = JSON.parse(
  readFileSync(fileURLToPath(new URL('../package.json', import.meta.url)), 'utf8'),
)
const deps = { ...pkg.dependencies, ...pkg.devDependencies }

const expected = [
  ['vite', 8],
  ['vitest', 5],
  ['@vitejs/plugin-react', 6],
  ['jsdom', 30],
  ['@vitest/coverage-v8', 5],
]

let failed = false
for (const [name, major] of expected) {
  const raw = deps[name]
  const found = raw == null ? NaN : Number(String(raw).replace(/^[\^~]/, '').split('.')[0])
  console.log(`${name} ${Number.isNaN(found) ? 'missing' : found}`)
  if (found !== major) failed = true
}

if (failed) process.exit(1)
