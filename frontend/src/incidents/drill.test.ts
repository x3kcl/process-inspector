import { describe, expect, it } from 'vitest'
import { incidentSearchParams } from './drill'

describe('incidentSearchParams', () => {
  it('scopes the search to every engine the incident touched, FAILED+RETRYING, by signature', () => {
    const params = new URLSearchParams(
      incidentSearchParams({
        signatureHash: 'abc123',
        countsByEngine: { 'engine-b': { 'p:v1': 3 }, 'engine-a': { 'p:v2': 1 } },
      }),
    )
    expect(params.get('signature')).toBe('abc123')
    expect(params.get('engines')).toBe('engine-a,engine-b')
    expect(params.get('status')).toBe('FAILED,RETRYING')
  })

  it('produces an empty-engines scope when the incident carries none', () => {
    const params = new URLSearchParams(incidentSearchParams({ signatureHash: 'abc123' }))
    expect(params.get('signature')).toBe('abc123')
    expect(params.has('engines')).toBe(false)
  })
})
