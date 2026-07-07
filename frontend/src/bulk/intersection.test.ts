import { describe, expect, it } from 'vitest'
import type { ProcessInstanceRow } from '../api/model'
import { BULK_CAP, perEngineSplit, planFilterScope, planSelection } from './intersection'

function row(partial: Partial<ProcessInstanceRow>): ProcessInstanceRow {
  return { engineId: 'engine-a', processInstanceId: 'pi', protectedInstance: false, ...partial }
}

const FAILED = row({
  processInstanceId: 'pi-failed',
  businessKey: 'order-1',
  status: 'FAILED',
  flags: { hasDeadLetterJobs: true },
})
const ACTIVE = row({ processInstanceId: 'pi-active', status: 'ACTIVE', flags: {} })
const SUSPENDED = row({
  processInstanceId: 'pi-susp',
  status: 'SUSPENDED',
  flags: { suspended: true },
})
const ENDED = row({ processInstanceId: 'pi-done', status: 'COMPLETED', flags: { ended: true } })

describe('planSelection — the Intersection Rule (binding)', () => {
  it('offers retry only when EVERY row has dead-letter evidence', () => {
    const allFailed = planSelection([FAILED, { ...FAILED, processInstanceId: 'pi-2' }])
    expect(allFailed.offers.find((o) => o.verb === 'retry-job')?.enabled).toBe(true)

    const mixed = planSelection([FAILED, ACTIVE])
    const retry = mixed.offers.find((o) => o.verb === 'retry-job')
    expect(retry?.enabled).toBe(false)
    expect(retry?.reason).toContain('pi-active')
    expect(retry?.reason).toContain('no dead-letter job')
  })

  it('names the offending row by business key when it has one', () => {
    const mixed = planSelection([ACTIVE, { ...ENDED, businessKey: 'order-9' }])
    const suspend = mixed.offers.find((o) => o.verb === 'suspend')
    expect(suspend?.enabled).toBe(false)
    expect(suspend?.reason).toContain('order-9')
  })

  it('suspend excludes suspended and ended rows; activate is the inverse', () => {
    const plan = planSelection([SUSPENDED])
    expect(plan.offers.find((o) => o.verb === 'suspend')?.enabled).toBe(false)
    expect(plan.offers.find((o) => o.verb === 'activate')?.enabled).toBe(true)
    const active = planSelection([ACTIVE, FAILED])
    expect(active.offers.find((o) => o.verb === 'suspend')?.enabled).toBe(true)
    expect(active.offers.find((o) => o.verb === 'activate')?.enabled).toBe(false)
  })

  it('auto-excludes protected rows and counts them for the badge', () => {
    const plan = planSelection([
      FAILED,
      { ...FAILED, processInstanceId: 'pi-p', protectedInstance: true },
    ])
    expect(plan.targets).toHaveLength(1)
    expect(plan.protectedExcluded).toBe(1)
    expect(plan.offers.find((o) => o.verb === 'retry-job')?.enabled).toBe(true)
  })

  it('an all-protected selection disables everything with the protection named', () => {
    const plan = planSelection([{ ...FAILED, protectedInstance: true }])
    expect(plan.targets).toHaveLength(0)
    expect(plan.offers.every((o) => !o.enabled)).toBe(true)
    expect(plan.offers[0].reason).toContain('protected')
  })

  it('counts unknown protection separately — submitted, settled by the BFF guard', () => {
    const plan = planSelection([{ ...FAILED, protectedInstance: undefined }])
    expect(plan.targets).toHaveLength(1)
    expect(plan.protectionUnknown).toBe(1)
  })

  it('disables everything over the 200-item cap with the deselect count', () => {
    const many = Array.from({ length: BULK_CAP + 3 }, (_, i) =>
      row({
        processInstanceId: `pi-${String(i)}`,
        status: 'FAILED',
        flags: { hasDeadLetterJobs: true },
      }),
    )
    const plan = planSelection(many)
    expect(plan.overCap).toBe(true)
    expect(plan.offers[0].enabled).toBe(false)
    expect(plan.offers[0].reason).toContain('deselect 3')
  })
})

describe('planFilterScope (v1.x #2 — eligibility from status chips only)', () => {
  const offer = (offers: ReturnType<typeof planFilterScope>, verb: string) =>
    offers.find((o) => o.verb === verb)

  it('offers retry-job only for a pure FAILED filter', () => {
    const offers = planFilterScope(['FAILED'])
    expect(offer(offers, 'retry-job')?.enabled).toBe(true)
    expect(offer(offers, 'suspend')?.enabled).toBe(true) // FAILED rows are open
    expect(offer(offers, 'activate')?.enabled).toBe(false)
  })

  it('disables retry-job when RETRYING is mixed in, naming the offender', () => {
    const offers = planFilterScope(['FAILED', 'RETRYING'])
    const retry = offer(offers, 'retry-job')
    expect(retry?.enabled).toBe(false)
    expect(retry?.reason).toContain('RETRYING')
  })

  it('offers activate only for a pure SUSPENDED filter', () => {
    const offers = planFilterScope(['SUSPENDED'])
    expect(offer(offers, 'activate')?.enabled).toBe(true)
    expect(offer(offers, 'suspend')?.enabled).toBe(false)
    expect(offer(offers, 'retry-job')?.enabled).toBe(false)
  })

  it('disables everything when COMPLETED is in the filter', () => {
    const offers = planFilterScope(['ACTIVE', 'COMPLETED'])
    expect(offers.every((o) => !o.enabled)).toBe(true)
    expect(offers[0].reason).toContain('COMPLETED')
  })

  it('disables everything without explicit status chips — greyed, never hidden', () => {
    for (const offers of [planFilterScope([]), planFilterScope(undefined)]) {
      expect(offers).toHaveLength(3)
      expect(offers.every((o) => !o.enabled)).toBe(true)
      expect(offers[0].reason).toContain('status chips')
    }
  })
})

describe('perEngineSplit', () => {
  it('enumerates the scope per engine, sorted', () => {
    const split = perEngineSplit([
      row({ engineId: 'b' }),
      row({ engineId: 'a' }),
      row({ engineId: 'b' }),
    ])
    expect(split).toEqual([
      ['a', 1],
      ['b', 2],
    ])
  })
})
