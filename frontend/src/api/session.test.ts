// Usability W3: the explicit sign-out store that gates SignIn independently of the 401 chain.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { isSignedOut, setSignedOut, subscribeSignedOut } from './session'

afterEach(() => {
  setSignedOut(false)
})

describe('session sign-out store', () => {
  it('defaults to signed-in and toggles', () => {
    expect(isSignedOut()).toBe(false)
    setSignedOut(true)
    expect(isSignedOut()).toBe(true)
  })

  it('notifies subscribers only on a real change', () => {
    const listener = vi.fn()
    const unsub = subscribeSignedOut(listener)
    setSignedOut(true)
    setSignedOut(true) // no-op — same value
    setSignedOut(false)
    unsub()
    setSignedOut(true) // after unsubscribe — not counted
    expect(listener).toHaveBeenCalledTimes(2)
  })
})
