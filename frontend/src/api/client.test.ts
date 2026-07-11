// The pure half of the cookie-session CSRF middleware (usability W1#1, Theme T7):
// cookie-session users must echo Spring's XSRF-TOKEN cookie as X-XSRF-TOKEN on unsafe
// methods, while Basic-per-request stays CSRF-exempt on the BFF. The DOM side
// (document.cookie + the live request) is Playwright's territory.
import { describe, expect, it } from 'vitest'
import { xsrfHeaderValue } from './client'

describe('xsrfHeaderValue', () => {
  it('echoes the XSRF-TOKEN cookie on unsafe methods when no Basic token is present', () => {
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
      expect(xsrfHeaderValue(method, 'XSRF-TOKEN=abc-123', null)).toBe('abc-123')
    }
  })

  it('never touches safe methods — GET/HEAD/OPTIONS need no CSRF proof', () => {
    for (const method of ['GET', 'HEAD', 'OPTIONS']) {
      expect(xsrfHeaderValue(method, 'XSRF-TOKEN=abc-123', null)).toBeNull()
    }
  })

  it('stays out of the way when a Basic token is present (CSRF-exempt on the BFF)', () => {
    expect(xsrfHeaderValue('POST', 'XSRF-TOKEN=abc-123', 'ZGV2OmRldg==')).toBeNull()
  })

  it('is method-case-insensitive (fetch normalises, callers may not)', () => {
    expect(xsrfHeaderValue('post', 'XSRF-TOKEN=abc-123', null)).toBe('abc-123')
  })

  it('finds the token among other cookies and never matches a suffix-named cookie', () => {
    expect(xsrfHeaderValue('POST', 'SESSION=s1; XSRF-TOKEN=abc-123; theme=dark', null)).toBe(
      'abc-123',
    )
    expect(xsrfHeaderValue('POST', 'NOT-XSRF-TOKEN=evil', null)).toBeNull()
  })

  it('URL-decodes the cookie value (Spring cookie values may be encoded)', () => {
    expect(xsrfHeaderValue('POST', 'XSRF-TOKEN=a%2Bb%3D%3D', null)).toBe('a+b==')
  })

  it('echoes a malformed percent-encoding raw instead of throwing (external review)', () => {
    expect(xsrfHeaderValue('POST', 'XSRF-TOKEN=abc%zz', null)).toBe('abc%zz')
  })

  it('answers null when the cookie is absent or empty — send nothing rather than garbage', () => {
    expect(xsrfHeaderValue('POST', '', null)).toBeNull()
    expect(xsrfHeaderValue('POST', 'SESSION=s1', null)).toBeNull()
    expect(xsrfHeaderValue('POST', 'XSRF-TOKEN=', null)).toBeNull()
  })
})
