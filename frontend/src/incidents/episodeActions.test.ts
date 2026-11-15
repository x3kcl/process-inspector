// #662: the per-episode "actions during this episode" aggregate (served since #358) is
// rendered on the episode row, with a deep link into the Ops log from the episode start.
import { describe, expect, it } from 'vitest'
import { episodeActionsLine, episodeOpsLogHref } from './episodeActions'

describe('episodeActionsLine', () => {
  it('names count, kinds and outcomes', () => {
    expect(episodeActionsLine({ count: 6, byVerb: { 'retry-job': 6 }, byOutcome: { ok: 6 } })).toBe(
      '6 actions during this episode: retry-job ×6 · ok 6',
    )
  })

  it('lists several kinds, largest first', () => {
    expect(
      episodeActionsLine({
        count: 3,
        byVerb: { suspend: 1, 'retry-job': 2 },
        byOutcome: { ok: 2, failed: 1 },
      }),
    ).toBe('3 actions during this episode: retry-job ×2 · suspend ×1 · ok 2 · failed 1')
  })

  it('says one action in the singular', () => {
    expect(episodeActionsLine({ count: 1, byVerb: { suspend: 1 }, byOutcome: { ok: 1 } })).toBe(
      '1 action during this episode: suspend ×1 · ok 1',
    )
  })

  it('marks a capped tally as a lower bound', () => {
    expect(
      episodeActionsLine({
        count: 500,
        byVerb: { 'retry-job': 500 },
        byOutcome: {},
        truncated: true,
      }),
    ).toBe('≥ 500 actions during this episode: retry-job ×500')
  })

  it('states zero plainly', () => {
    expect(episodeActionsLine({ count: 0, byVerb: {}, byOutcome: {} })).toBe(
      'no actions during this episode',
    )
  })

  it('renders nothing when the server sent no attribution', () => {
    expect(episodeActionsLine(undefined)).toBe('')
  })
})

describe('episodeOpsLogHref', () => {
  it('opens the Ops log from the episode start', () => {
    expect(episodeOpsLogHref('2026-08-09T10:00:00Z')).toBe('/audit?since=2026-08-09T10%3A00%3A00Z')
  })

  it('falls back to the unfiltered log without a start', () => {
    expect(episodeOpsLogHref(undefined)).toBe('/audit')
  })
})
