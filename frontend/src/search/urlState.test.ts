import { describe, expect, it } from 'vitest'
import type { SearchRequest } from '../api/model'
import {
  decodeHiddenColumns,
  decodeSearch,
  encodeHiddenColumns,
  encodeSearch,
  hasSearch,
} from './urlState'

describe('search URL codec', () => {
  it('round-trips a fully loaded request', () => {
    const request: SearchRequest = {
      engineIds: ['engine-a', 'engine-b'],
      statuses: ['FAILED', 'RETRYING'],
      processDefinitionKey: 'orderFulfilment',
      definitionVersion: 42,
      businessKey: 'ORD-4711',
      businessKeyLike: 'ORD-',
      startedAfter: '2026-07-01T00:00:00Z',
      startedBefore: '2026-07-06T00:00:00Z',
      failureTimeAfter: '2026-07-05T00:00:00Z',
      failureTimeBefore: '2026-07-06T12:00:00Z',
      errorText: 'SocketTimeout',
      currentActivity: 'chargeCard',
      sortBy: 'failureTime',
      pageSize: 50,
      variables: [{ name: 'orderId', value: 'X-1', operation: 'equals', type: 'string' }],
    }
    expect(decodeSearch(encodeSearch(request))).toEqual(request)
  })

  // #279: the signature generation stamp rides alongside `signature` and round-trips.
  it('round-trips the signature generation stamp beside the signature', () => {
    const request: SearchRequest = {
      statuses: ['FAILED', 'RETRYING'],
      signatureHash: 'a'.repeat(64),
      signatureAlgoVersion: 2,
    }
    const params = encodeSearch(request)
    expect(params.get('signature')).toBe('a'.repeat(64))
    expect(params.get('algo')).toBe('2')
    expect(decodeSearch(params)).toEqual(request)
  })

  it('drops the generation stamp when there is no signature to bind it to', () => {
    const params = encodeSearch({ statuses: ['FAILED'], signatureAlgoVersion: 2 })
    expect(params.has('algo')).toBe(false)
  })

  it('treats a legacy signature link (no algo param) as an unstamped signatureAlgoVersion', () => {
    // An old bookmark from before #279 — assumed-unknown generation; the BFF surfaces the reason.
    const decoded = decodeSearch(new URLSearchParams('status=FAILED&signature=' + 'b'.repeat(64)))
    expect(decoded?.signatureHash).toBe('b'.repeat(64))
    expect(decoded?.signatureAlgoVersion).toBeUndefined()
  })

  it('ignores a bare algo param with no signature (not a search trigger, not decoded)', () => {
    const params = new URLSearchParams('algo=2')
    expect(hasSearch(params)).toBe(false)
    // Even if some other filter is present, algo is read only beside a signature.
    const decoded = decodeSearch(new URLSearchParams('status=FAILED&algo=2'))
    expect(decoded?.signatureAlgoVersion).toBeUndefined()
  })

  it('round-trips through actual URL serialization (the shareable-link path)', () => {
    const request: SearchRequest = {
      statuses: ['FAILED'],
      businessKeyLike: 'ORD & sons/№7?',
      errorText: 'timeout: read',
    }
    const url = `?${encodeSearch(request).toString()}`
    const decoded = decodeSearch(new URLSearchParams(url))
    expect(decoded?.businessKeyLike).toBe('ORD & sons/№7?')
    expect(decoded?.errorText).toBe('timeout: read')
    expect(decoded?.statuses).toEqual(['FAILED'])
  })

  it('round-trips the error-class signature (the triage class drill)', () => {
    const request: SearchRequest = {
      statuses: ['FAILED', 'RETRYING'],
      signatureHash: 'a'.repeat(64),
    }
    const decoded = decodeSearch(new URLSearchParams(`?${encodeSearch(request).toString()}`))
    expect(decoded?.signatureHash).toBe('a'.repeat(64))
    expect(hasSearch(encodeSearch({ signatureHash: 'abc' }))).toBe(true)
  })

  it('returns null when the URL carries no search', () => {
    expect(decodeSearch(new URLSearchParams())).toBeNull()
    expect(hasSearch(new URLSearchParams('unrelated=1'))).toBe(false)
  })

  it('omits empty fields from the URL', () => {
    const params = encodeSearch({ statuses: ['FAILED'], businessKey: '', engineIds: [] })
    expect(params.toString()).toBe('status=FAILED')
  })

  it('drops unknown statuses instead of sending garbage to the BFF', () => {
    const decoded = decodeSearch(new URLSearchParams('status=FAILED,BOGUS'))
    expect(decoded?.statuses).toEqual(['FAILED'])
  })

  it('survives malformed vars JSON', () => {
    const decoded = decodeSearch(new URLSearchParams('status=ACTIVE&vars=%7Bnot-json'))
    expect(decoded?.variables).toBeUndefined()
  })

  it('ignores a non-numeric pageSize', () => {
    const decoded = decodeSearch(new URLSearchParams('status=ACTIVE&pageSize=lots'))
    expect(decoded?.pageSize).toBeUndefined()
  })

  it('round-trips the definition version (#233 per-version drill)', () => {
    const request: SearchRequest = {
      statuses: ['FAILED'],
      processDefinitionKey: 'orderFulfilment',
      definitionVersion: 7,
    }
    const decoded = decodeSearch(new URLSearchParams(`?${encodeSearch(request).toString()}`))
    expect(decoded?.definitionVersion).toBe(7)
    // A version param alone counts as a search — the BFF then rejects it loudly
    // (definitionVersion requires processDefinitionKey) instead of the codec silently
    // widening the scope by dropping it.
    expect(hasSearch(new URLSearchParams('version=7'))).toBe(true)
  })

  it('ignores a non-numeric version instead of sending NaN to the BFF', () => {
    const decoded = decodeSearch(new URLSearchParams('definitionKey=order&version=latest'))
    expect(decoded?.definitionVersion).toBeUndefined()
  })

  it('drops partially-numeric versions instead of silently reinterpreting them', () => {
    // parseInt would turn these into v42 — a wrong-target of its own (#233 review).
    for (const garbage of ['42xyz', '42.9', '-42', '0', 'latest', ' ']) {
      const decoded = decodeSearch(new URLSearchParams(`definitionKey=order&version=${garbage}`))
      expect(decoded?.definitionVersion, `version=${garbage}`).toBeUndefined()
    }
  })

  it('honors an exact-integer scientific spelling instead of dropping it', () => {
    // "4e2" IS 400 — dropping a value the user meant would silently widen the scope,
    // and parseInt would have silently read it as v4.
    const decoded = decodeSearch(new URLSearchParams('definitionKey=order&version=4e2'))
    expect(decoded?.definitionVersion).toBe(400)
  })

  it('omits the version param entirely when the request carries none', () => {
    const params = encodeSearch({ statuses: ['FAILED'], processDefinitionKey: 'order' })
    expect(params.has('version')).toBe(false)
  })
})

describe('column-layout codec (#197)', () => {
  it('round-trips a hidden-column set, sorted for determinism', () => {
    const params = new URLSearchParams()
    encodeHiddenColumns(params, new Set(['startTime', 'businessKey']))
    expect(params.get('cols')).toBe('businessKey,startTime')
    expect(decodeHiddenColumns(params)).toEqual(new Set(['startTime', 'businessKey']))
  })

  it('an empty set omits the param entirely rather than writing cols=', () => {
    const params = new URLSearchParams()
    encodeHiddenColumns(params, new Set())
    expect(params.has('cols')).toBe(false)
    expect(decodeHiddenColumns(params)).toBeNull()
  })

  it('distinguishes "no suggestion" (null) from "an explicit empty suggestion"', () => {
    expect(decodeHiddenColumns(new URLSearchParams('status=FAILED'))).toBeNull()
  })

  it('a bare cols param alone does not count as a search (Stage-0 routing untouched)', () => {
    expect(hasSearch(new URLSearchParams('cols=businessKey'))).toBe(false)
  })

  it('cols never leaks into the decoded SearchRequest sent to the BFF', () => {
    const decoded = decodeSearch(new URLSearchParams('status=FAILED&cols=businessKey,startTime'))
    expect(decoded).not.toBeNull()
    expect(decoded).not.toHaveProperty('cols')
    expect('cols' in (decoded ?? {})).toBe(false)
  })

  it('survives a malformed/empty cols value without throwing', () => {
    expect(decodeHiddenColumns(new URLSearchParams('cols='))).toEqual(new Set())
    expect(decodeHiddenColumns(new URLSearchParams('cols=,,'))).toEqual(new Set())
  })
})
