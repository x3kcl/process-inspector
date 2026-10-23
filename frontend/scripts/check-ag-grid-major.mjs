#!/usr/bin/env node
// CI gate for #456: ag-grid-community and ag-grid-react move together and stay on major 36.
import { readFileSync } from 'node:fs'

const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
const names = ['ag-grid-community', 'ag-grid-react']

let ok = true
for (const name of names) {
  const version = pkg.dependencies?.[name]
  const major = /^[^\d]*(\d+)\./.exec(String(version ?? ''))?.[1]
  console.log(`${name}: ${version ?? 'missing'}`)
  if (major !== '36') ok = false
}
if (!ok) {
  console.error('AG Grid major guard FAILED — both packages must be major 36.')
  process.exit(1)
}
console.log('AG Grid major guard OK - both packages are major 36.')
