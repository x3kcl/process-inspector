// @vitest-environment jsdom
// The detail sparkline's HONESTY markers. The scaling math is covered by timeline.test.ts; this
// only proves the two untrustworthy sample kinds actually reach the DOM with a distinct marker
// AND a legend line — never colour alone (SPEC §10a).
//
// The `blind` half is the review fix: `IncidentDetail.OccurrencePoint` carried `truncated` but
// not `cycleComplete`, so a sample written while an engine was unreachable drew an ordinary
// filled dot and its dip read as a real drain. Iron rule: never render a status derived from
// incomplete data without the badge.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { OccurrencePoint } from '../api/model'
import { IncidentTimeline } from './IncidentTimeline'

afterEach(cleanup)

const TRUNCATED_LEGEND = /hollow\/dashed points are lower bounds/
const BLIND_LEGEND = /recorded while an engine was unreachable/

describe('IncidentTimeline honesty markers', () => {
  it('marks a blind sample with its own shape and legend', () => {
    const series: OccurrencePoint[] = [
      { sampledAt: 't1', total: 1000, truncated: false, cycleComplete: true },
      { sampledAt: 't2', total: 100, truncated: false, cycleComplete: false },
      { sampledAt: 't3', total: 1000, truncated: false, cycleComplete: true },
    ]

    const { container } = render(<IncidentTimeline series={series} />)

    expect(container.querySelectorAll('.incident-timeline-point-blind')).toHaveLength(1)
    expect(container.querySelectorAll('.incident-timeline-point')).toHaveLength(2)
    expect(screen.getByText(BLIND_LEGEND)).toBeDefined()
    expect(screen.queryByText(TRUNCATED_LEGEND)).toBeNull()
  })

  it('keeps the truncated marker distinct from the blind one — they are different claims', () => {
    const series: OccurrencePoint[] = [
      { sampledAt: 't1', total: 5, truncated: true, cycleComplete: true },
      { sampledAt: 't2', total: 7, truncated: false, cycleComplete: false },
    ]

    const { container } = render(<IncidentTimeline series={series} />)

    expect(container.querySelectorAll('.incident-timeline-point-truncated')).toHaveLength(1)
    expect(container.querySelectorAll('.incident-timeline-point-blind')).toHaveLength(1)
    expect(screen.getByText(TRUNCATED_LEGEND)).toBeDefined()
    expect(screen.getByText(BLIND_LEGEND)).toBeDefined()
  })

  it('adds no marker and no legend when every sample was fully observed', () => {
    const series: OccurrencePoint[] = [
      { sampledAt: 't1', total: 5, truncated: false, cycleComplete: true },
      { sampledAt: 't2', total: 7, truncated: false, cycleComplete: true },
    ]

    const { container } = render(<IncidentTimeline series={series} />)

    expect(container.querySelectorAll('.incident-timeline-point')).toHaveLength(2)
    expect(container.querySelectorAll('.incident-timeline-point-blind')).toHaveLength(0)
    expect(screen.queryByText(BLIND_LEGEND)).toBeNull()
    expect(screen.queryByText(TRUNCATED_LEGEND)).toBeNull()
  })
})
