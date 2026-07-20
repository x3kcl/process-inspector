import { describe, expect, it } from 'vitest'
import { countStripLabel } from './liveness'

describe('countStripLabel (#270 R-NFR-08 honesty)', () => {
  it('calls the number live ONLY when a live Stage-0 join backs it', () => {
    expect(countStripLabel(true)).toEqual({ label: 'Live total', drillMayBeEmpty: false })
  })

  it('degrades to "Last observed" and warns about an empty drill when the live join is absent', () => {
    // The BFF omits `live` when the class is not failing now, its generation is retired, or
    // its slice is out of scope. In every one of those, `lastTotal` is a stale ledger sample
    // and the drill can legitimately match nothing — which is precisely the state the owner
    // hit as "Live total 8 instances" + "Search these instances -> 0".
    expect(countStripLabel(false)).toEqual({ label: 'Last observed', drillMayBeEmpty: true })
  })

  it('never claims live while also warning the drill may be empty', () => {
    for (const hasLive of [true, false]) {
      const { label, drillMayBeEmpty } = countStripLabel(hasLive)
      expect(label === 'Live total' && drillMayBeEmpty).toBe(false)
    }
  })
})
