import { describe, expect, it } from 'vitest'
import { glossTechnicalMessage } from './plainFailure'

describe('glossTechnicalMessage — usability round 1, Theme F', () => {
  it('glosses a dropped connection', () => {
    expect(glossTechnicalMessage('java.nio.channels.ClosedChannelException')).toBe(
      'the connection to the engine dropped mid-request',
    )
    expect(glossTechnicalMessage('Connection reset by peer')).toBe(
      'the connection to the engine dropped mid-request',
    )
    expect(glossTechnicalMessage('java.io.EOFException')).toBe(
      'the connection to the engine dropped mid-request',
    )
  })

  it('glosses a refused connection', () => {
    expect(glossTechnicalMessage('Connection refused')).toBe(
      'the engine is not accepting connections',
    )
    expect(glossTechnicalMessage('java.net.ConnectException: refused')).toBe(
      'the engine is not accepting connections',
    )
  })

  it('glosses a timeout', () => {
    expect(glossTechnicalMessage('Read timed out')).toBe('the engine did not answer in time')
    expect(glossTechnicalMessage('connect timeout after 5000ms')).toBe(
      'the engine did not answer in time',
    )
  })

  it('glosses an unknown host', () => {
    expect(glossTechnicalMessage('java.net.UnknownHostException: engine-b')).toBe(
      "the engine's address could not be found",
    )
  })

  it('glosses an open circuit breaker', () => {
    expect(glossTechnicalMessage('CircuitBreaker engine-b is OPEN')).toBe(
      'paused after repeated failures — retrying automatically',
    )
  })

  it('glosses 401/403 as a credentials rejection', () => {
    expect(glossTechnicalMessage('HTTP 401 Unauthorized')).toBe(
      "the engine rejected the inspector's credentials",
    )
    expect(glossTechnicalMessage('403 Forbidden')).toBe(
      "the engine rejected the inspector's credentials",
    )
  })

  it('glosses any 5xx as an internal engine error', () => {
    expect(glossTechnicalMessage('HTTP 503 Service Unavailable')).toBe(
      'the engine reported an internal error',
    )
    expect(glossTechnicalMessage('500 Internal Server Error')).toBe(
      'the engine reported an internal error',
    )
  })

  it('falls back to a generic gloss for anything unrecognized, and for undefined', () => {
    expect(glossTechnicalMessage('some brand new failure mode')).toBe(
      'an unexpected error — see technical detail',
    )
    expect(glossTechnicalMessage(undefined)).toBe('an unexpected error — see technical detail')
  })

  it('takes the FIRST hit when a message matches more than one rule', () => {
    // Contains both a timeout signature and a 5xx-looking substring — timeout wins.
    expect(glossTechnicalMessage('Read timed out (upstream reported 503)')).toBe(
      'the engine did not answer in time',
    )
  })
})
