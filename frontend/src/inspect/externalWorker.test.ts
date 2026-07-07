import { describe, expect, it } from 'vitest'
import type { EngineDto } from '../api/model'
import { externalWorkerLaneVisible } from './externalWorker'

/** The capability gate is the whole graceful-degradation contract (v1.x #7): the lane must be
 *  invisible on anything but a probed ≥ 6.8 engine — no empty lane, no spinner, no blind call. */
describe('externalWorkerLaneVisible', () => {
  const withCaps = (externalWorkerJobs: boolean): EngineDto => ({
    id: 'e',
    capabilities: { externalWorkerJobs },
  })

  it('shows only on a capability-supporting engine (Flowable ≥ 6.8)', () => {
    expect(externalWorkerLaneVisible(withCaps(true))).toBe(true)
  })

  it('hides on a pre-6.8 engine (capability probed false)', () => {
    expect(externalWorkerLaneVisible(withCaps(false))).toBe(false)
  })

  it('hides while the engine or its capabilities are unknown — never flash then retract', () => {
    const noCaps: EngineDto = { id: 'e' }
    const emptyCaps: EngineDto = { id: 'e', capabilities: {} }
    expect(externalWorkerLaneVisible(undefined)).toBe(false)
    expect(externalWorkerLaneVisible(noCaps)).toBe(false)
    expect(externalWorkerLaneVisible(emptyCaps)).toBe(false)
  })
})
