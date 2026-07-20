import { describe, expect, it } from 'vitest'
import { requestIdSentence, withRequestId } from './requestId'

describe('requestId — the one quotable-support-id wording (R-AUD-04, #272)', () => {
  it('appends the exact next-move sentence when the server sent an id', () => {
    expect(withRequestId('Something failed.', 'req-1')).toBe(
      'Something failed. Quote request ID req-1 to support.',
    )
  })

  it('leaves the sentence untouched when the server sent no id', () => {
    expect(withRequestId('Something failed.', undefined)).toBe('Something failed.')
    expect(withRequestId('Something failed.', '')).toBe('Something failed.')
  })

  it('requestIdSentence is the standalone form used by RequestIdNote', () => {
    expect(requestIdSentence('abc')).toBe('Quote request ID abc to support.')
  })
})
