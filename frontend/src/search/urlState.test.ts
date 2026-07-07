import { describe, expect, it } from 'vitest'
import type { SearchRequest } from '../api/model'
import { decodeSearch, encodeSearch, hasSearch } from './urlState'

describe('search URL codec', () => {
  it('round-trips a fully loaded request', () => {
    const request: SearchRequest = {
      engineIds: ['engine-a', 'engine-b'],
      statuses: ['FAILED', 'RETRYING'],
      processDefinitionKey: 'orderFulfilment',
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
})
