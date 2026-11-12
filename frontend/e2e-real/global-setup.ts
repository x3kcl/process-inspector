// Seeds this run's OWN fixtures over engine REST, tagged with a run-unique business-key prefix.
// Every mutating arc filters on that prefix, so the suite never touches anything it did not
// start here (issue #466: "no mutation on anything but seeded fixtures").
import { awaitDeadLetter, requireDefinition, startInstance } from './engines'
import type { EngineId } from './engines'

export const PAGE_FIXTURES = 25

export default async function globalSetup(): Promise<void> {
  const run = `E2E-${Date.now().toString(36).toUpperCase()}`
  process.env.E2E_RUN = run

  for (const engine of ['engine-a', 'engine-b'] as const) {
    for (const key of ['demoFailingPayment', 'demoUserTask', 'acmeApiOutage']) {
      await requireDefinition(engine, key)
    }
  }

  // Arc 1: one dead-lettered ArithmeticException (divisor 0) to fix and retry.
  const retry = await startInstance('engine-a', 'demoFailingPayment', `${run}-RETRY`, [
    { name: 'amount', type: 'integer', value: 100 },
    { name: 'divisor', type: 'integer', value: 0 },
  ])

  // Arc 2: enough parked user-task instances, split across both engines, to need two
  // "Load more" pages at pageSize 10 — the K-way merge crosses engines on every page.
  for (let n = 0; n < PAGE_FIXTURES; n++) {
    const engine: EngineId = n % 2 === 0 ? 'engine-a' : 'engine-b'
    await startInstance(engine, 'demoUserTask', `${run}-PAGE-${String(n).padStart(2, '0')}`)
  }

  // Arc 3: FIX-ACME-06 (acmeApiOutage) — a deterministic integration dead-letter per engine.
  const acmeA = await startInstance('engine-a', 'acmeApiOutage', `${run}-ACME-A`)
  const acmeB = await startInstance('engine-b', 'acmeApiOutage', `${run}-ACME-B`)

  await Promise.all([
    awaitDeadLetter('engine-a', retry),
    awaitDeadLetter('engine-a', acmeA),
    awaitDeadLetter('engine-b', acmeB),
  ])
}
