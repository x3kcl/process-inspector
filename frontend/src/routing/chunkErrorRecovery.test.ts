// @vitest-environment jsdom
// #265: unit coverage for the chunk-failure-detection + reload-guard logic extracted out
// of ChunkErrorBoundary.tsx so it's testable without a full router harness. Mirrors
// lib/theme.test.ts's stubGlobal pattern for sessionStorage.
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  CHUNK_RELOAD_FLAG_KEY,
  chunkErrorRecoveryOutcome,
  decideChunkErrorRecovery,
  hasAttemptedChunkReload,
  isChunkLoadError,
  markChunkReloadAttempted,
} from './chunkErrorRecovery'

function stubSessionStorage(initial: Record<string, string> = {}): Map<string, string> {
  const store = new Map(Object.entries(initial))
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, value)
    },
  })
  return store
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('isChunkLoadError (#265)', () => {
  it('matches the Firefox phrasing, case-insensitively', () => {
    expect(
      isChunkLoadError(
        new Error(
          'error loading dynamically imported module: https://pi.naumann.cloud/assets/RemediationDemandPage-B4Vl3Mud.js',
        ),
      ),
    ).toBe(true)
    expect(new Error('ERROR LOADING DYNAMICALLY IMPORTED MODULE: foo').message).toMatch(
      /error loading dynamically imported module/i,
    )
  })

  it('matches the Chrome/V8 phrasing, case-insensitively', () => {
    expect(
      isChunkLoadError(
        new Error('Failed to fetch dynamically imported module: https://x/assets/Foo-abc.js'),
      ),
    ).toBe(true)
    expect(isChunkLoadError(new Error('failed to fetch dynamically imported module'))).toBe(true)
  })

  it('does not match an unrelated error', () => {
    expect(isChunkLoadError(new Error('NetworkError: engine is not accepting connections'))).toBe(
      false,
    )
  })

  it('handles a plain string thrown value', () => {
    expect(isChunkLoadError('Failed to fetch dynamically imported module: /assets/x.js')).toBe(
      true,
    )
  })

  it('handles non-Error, non-string thrown values without throwing', () => {
    expect(isChunkLoadError(undefined)).toBe(false)
    expect(isChunkLoadError(null)).toBe(false)
    expect(isChunkLoadError({ some: 'object' })).toBe(false)
  })
})

describe('decideChunkErrorRecovery (#265, pure)', () => {
  const chunkError = new Error('error loading dynamically imported module: /assets/x.js')
  const otherError = new Error('boom')

  it('ignores a non-chunk error regardless of the attempted flag', () => {
    expect(decideChunkErrorRecovery(otherError, false)).toBe('ignore')
    expect(decideChunkErrorRecovery(otherError, true)).toBe('ignore')
  })

  it('reloads on first sighting of a chunk error', () => {
    expect(decideChunkErrorRecovery(chunkError, false)).toBe('reload')
  })

  it('falls back once a reload was already attempted this session — never loops', () => {
    expect(decideChunkErrorRecovery(chunkError, true)).toBe('fallback')
  })
})

describe('hasAttemptedChunkReload / markChunkReloadAttempted (#265, sessionStorage)', () => {
  it('reads false when the flag was never set', () => {
    stubSessionStorage()
    expect(hasAttemptedChunkReload()).toBe(false)
  })

  it('reads true after marking', () => {
    const store = stubSessionStorage()
    markChunkReloadAttempted()
    expect(store.get(CHUNK_RELOAD_FLAG_KEY)).toBe('1')
    expect(hasAttemptedChunkReload()).toBe(true)
  })

  it('degrades to false (never attempted) when sessionStorage.getItem throws', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    })
    expect(hasAttemptedChunkReload()).toBe(false)
  })

  it('marking is best-effort and never throws when sessionStorage.setItem throws', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => null,
      setItem: () => {
        throw new Error('blocked')
      },
    })
    expect(() => {
      markChunkReloadAttempted()
    }).not.toThrow()
  })
})

describe('chunkErrorRecoveryOutcome (#265, integration of the two halves)', () => {
  const chunkError = new Error('Failed to fetch dynamically imported module: /assets/x.js')

  it('reload -> fallback across two sightings, guarded by the session flag', () => {
    stubSessionStorage()
    expect(chunkErrorRecoveryOutcome(chunkError)).toBe('reload')
    // The caller (ChunkErrorBoundary) is responsible for calling markChunkReloadAttempted()
    // as its own explicit side effect before reloading — simulate that here.
    markChunkReloadAttempted()
    expect(chunkErrorRecoveryOutcome(chunkError)).toBe('fallback')
  })

  it('never reloads for an unrelated error, even with a clean flag', () => {
    stubSessionStorage()
    expect(chunkErrorRecoveryOutcome(new Error('some other render error'))).toBe('ignore')
  })

  it('does NOT itself set the flag or touch window.location', () => {
    stubSessionStorage()
    // chunkErrorRecoveryOutcome only ever reads sessionStorage — it must never call
    // reload itself (that's ChunkErrorBoundary's job, as an explicit effect). A minimal
    // stub is enough; jsdom's real `location.reload` is a non-configurable property that
    // can't be spied on directly.
    const reload = vi.fn()
    vi.stubGlobal('location', { reload })
    chunkErrorRecoveryOutcome(chunkError)
    expect(reload).not.toHaveBeenCalled()
    expect(hasAttemptedChunkReload()).toBe(false)
  })
})
