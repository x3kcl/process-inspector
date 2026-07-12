// @vitest-environment jsdom
// R-L3-01 (usability W3-4): the status chip is interactive — clicking it opens the falsifiable,
// re-derived "Explain this status" evidence (per-leg + per-flag), with a deep link to the
// failing child when a status is FAILED "in subprocess". A chip with no instance identity stays
// a plain, non-interactive label.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { StatusEvidence } from '../api/model'
import { StatusChip } from './StatusChip'

const evidence: StatusEvidence = {
  compositeId: 'engine-a:pi-root',
  engineId: 'engine-a',
  processInstanceId: 'pi-root',
  status: 'FAILED',
  flags: {
    ended: false,
    suspended: false,
    hasDeadLetterJobs: false,
    hasFailingJobs: false,
    failedInSubprocess: true,
  },
  plan: 'LIVE_LANE_SCAN',
  planReason: 'Instance is open, so every failure lane is scanned plus a descendant walk.',
  rederived: true,
  rederivedAt: '2026-07-11T12:00:00.000Z',
  note: 'Re-derived just now. The inspector does not retain the original response bytes.',
  findings: [
    {
      flag: 'failedInSubprocess',
      value: true,
      source: 'descendant walk',
      detail: 'call-activity child pi-child holds dead-letter job job-9',
      deepLinkInstanceId: 'pi-child',
    },
  ],
  legs: [
    {
      label: 'descendant walk — child query',
      method: 'POST',
      url: 'http://engine/query/historic-process-instances',
      requestBody: '{"superProcessInstanceId":"pi-root"}',
      status: 200,
      durationMs: 12,
      asOf: '2026-07-11T12:00:00.000Z',
      source: 'live',
    },
  ],
}

const useEvidence = vi.fn()
vi.mock('../inspect/useInstanceQueries', () => ({
  useInstanceStatusEvidence: (...args: unknown[]) => useEvidence(...args) as unknown,
}))

afterEach(cleanup)

describe('StatusChip — Explain this status (R-L3-01)', () => {
  it('renders a plain, non-interactive label when given no instance identity', () => {
    render(<StatusChip status="ACTIVE" />)
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByText('ACTIVE')).toBeTruthy()
  })

  it('renders TERMINATED (not COMPLETED) for an ended instance that was terminated (#118/#105)', () => {
    // Flowable ends both completed AND terminated instances with an endTime → status is COMPLETED,
    // but the chip must not lie: with a termination reason it reads TERMINATED, reason in the tooltip.
    render(<StatusChip status="COMPLETED" terminationReason="cancelled by ops — duplicate order" />)
    const chip = screen.getByText('TERMINATED')
    expect(chip.className).toContain('terminated')
    expect(chip.getAttribute('title')).toContain('cancelled by ops — duplicate order')
    expect(screen.queryByText('COMPLETED')).toBeNull()
  })

  it('still reads COMPLETED for a genuine completion (no termination reason)', () => {
    render(<StatusChip status="COMPLETED" />)
    expect(screen.getByText('COMPLETED')).toBeTruthy()
    expect(screen.queryByText('TERMINATED')).toBeNull()
  })

  it('opens the re-derived evidence view with per-flag provenance and the child deep link', () => {
    useEvidence.mockReturnValue({ data: evidence, isPending: false, isError: false })
    render(
      <MemoryRouter>
        <StatusChip status="FAILED" engineId="engine-a" instanceId="pi-root" />
      </MemoryRouter>,
    )

    const chip = screen.getByRole('button', { name: /explain this status/i })
    expect(chip.getAttribute('title')).toBe('Explain this status')

    fireEvent.click(chip)

    // Labeled as re-derived (never pretending the original bytes were retained).
    expect(screen.getByText('Re-derived just now.', { selector: 'strong' })).toBeTruthy()
    expect(screen.getByText(/does not retain the original response bytes/)).toBeTruthy()
    // Plan choice + per-flag provenance.
    expect(screen.getByText('LIVE_LANE_SCAN')).toBeTruthy()
    expect(screen.getByText(/child pi-child holds dead-letter job job-9/)).toBeTruthy()
    // Raw per-leg evidence.
    expect(screen.getByText(/POST http:\/\/engine\/query\/historic-process-instances/)).toBeTruthy()
    // Deep link to the failing child's Errors & Jobs (the retest contradiction, made explainable).
    const link = screen.getByRole('link', { name: /failing child's Errors & Jobs/i })
    expect(link.getAttribute('href')).toBe('/inspect/engine-a/pi-child?tab=errors-jobs')
  })
})
