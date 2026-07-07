import { describe, expect, it } from 'vitest'
import { classifySiblingError, selectComparePane } from './siblingState'

const nearestOk = (found: boolean | undefined) => ({ isPending: false, isError: false, found })
const nearestPending = { isPending: true, isError: false, found: undefined }
const nearestFailed = { isPending: false, isError: true, found: undefined }
const diffIdle = { isPending: false, isError: false, error: null }

describe('classifySiblingError', () => {
  it('treats a 404 (no such instance on this engine) as operator-fixable input', () => {
    expect(classifySiblingError({ status: 404 })).toBe('not-found')
  })

  it('treats a 400 (malformed id) as operator-fixable input', () => {
    expect(classifySiblingError({ status: 400 })).toBe('not-found')
  })

  it('treats a 500 as infra', () => {
    expect(classifySiblingError({ status: 500 })).toBe('infra')
  })

  it('treats a raw network reject (no status) as infra', () => {
    expect(classifySiblingError(new TypeError('Failed to fetch'))).toBe('infra')
  })
})

describe('selectComparePane — nearest-sibling honesty (Theme 2)', () => {
  it('shows a fetch-error pane, NOT the empty state, when the auto-suggest query fails', () => {
    // The regression this guards: a 500/404/network failure on nearest-sibling used to
    // collapse to "no completed sibling was found", a false negative the operator trusts.
    const pane = selectComparePane({
      siblingSelected: false,
      nearest: nearestFailed,
      diff: diffIdle,
    })
    expect(pane).toEqual({ kind: 'nearest-error' })
  })

  it('shows the domain empty state only when the backend explicitly answers found=false', () => {
    const pane = selectComparePane({
      siblingSelected: false,
      nearest: nearestOk(false),
      diff: diffIdle,
    })
    expect(pane).toEqual({ kind: 'no-sibling' })
  })

  it('shows the pending pane while the auto-suggest is in flight', () => {
    expect(
      selectComparePane({ siblingSelected: false, nearest: nearestPending, diff: diffIdle }),
    ).toEqual({ kind: 'nearest-pending' })
  })

  it('ignores a stale auto-suggest failure once a sibling is selected', () => {
    const pane = selectComparePane({
      siblingSelected: true,
      nearest: nearestFailed,
      diff: { isPending: false, isError: false, error: null },
    })
    expect(pane).toEqual({ kind: 'diff-ready' })
  })
})

describe('selectComparePane — diff query', () => {
  it('is pending while the diff computes', () => {
    expect(
      selectComparePane({
        siblingSelected: true,
        nearest: nearestOk(true),
        diff: { isPending: true, isError: false, error: null },
      }),
    ).toEqual({ kind: 'diff-pending' })
  })

  it('classifies a bad pasted sibling id (404) as not-found so the operator can fix it', () => {
    expect(
      selectComparePane({
        siblingSelected: true,
        nearest: nearestOk(true),
        diff: { isPending: false, isError: true, error: { status: 404 } },
      }),
    ).toEqual({ kind: 'diff-error', errorKind: 'not-found' })
  })

  it('classifies a 500 on the diff as infra', () => {
    expect(
      selectComparePane({
        siblingSelected: true,
        nearest: nearestOk(true),
        diff: { isPending: false, isError: true, error: { status: 500 } },
      }),
    ).toEqual({ kind: 'diff-error', errorKind: 'infra' })
  })
})
