// @vitest-environment jsdom
// U5 (#88): the shared reason/typed-token guard logic, tested ONCE here instead of
// re-asserting the same three behaviors ("reason too short disables", "token must match
// exactly", "no token means never gated") in every one of the ~13 modals that use it.
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { businessKeyOrInstanceToken, useProdGuard } from './guard'

describe('useProdGuard', () => {
  it('requires the reason to reach minLength when the rule requires it', () => {
    const { result } = renderHook(() =>
      useProdGuard({ reasonRule: { required: true, minLength: 10 }, environment: 'dev' }),
    )
    expect(result.current.reasonOk).toBe(false) // blank fails when required

    act(() => {
      result.current.setReason('too short')
    })
    expect(result.current.reasonOk).toBe(false) // 9 chars

    act(() => {
      result.current.setReason('exactly 10')
    })
    expect(result.current.reasonOk).toBe(true)
  })

  it('a blank reason is OK when the rule does not require one', () => {
    const { result } = renderHook(() =>
      useProdGuard({ reasonRule: { required: false, minLength: 10 }, environment: 'dev' }),
    )
    expect(result.current.reasonOk).toBe(true) // blank + not required = ok

    act(() => {
      result.current.setReason('short')
    })
    expect(result.current.reasonOk).toBe(false) // once non-blank, still must clear minLength
  })

  it('needsToken is false off-prod even when expectedToken is set — never gated outside prod', () => {
    const { result } = renderHook(() =>
      useProdGuard({
        reasonRule: { required: true, minLength: 10 },
        environment: 'dev',
        expectedToken: 'order-4711',
      }),
    )
    expect(result.current.prod).toBe(false)
    expect(result.current.needsToken).toBe(false)
    expect(result.current.tokenOk).toBe(true) // vacuously true — never blocks a non-token gate
  })

  it('needsToken:true overrides the prod-only default — a dangerous action can demand the token in every environment', () => {
    const { result } = renderHook(() =>
      useProdGuard({
        reasonRule: { required: true, minLength: 10 },
        environment: 'dev', // NOT prod
        expectedToken: 'engine-a',
        needsToken: true, // e.g. registry remove/purge — always gated, per LifecycleModal
      }),
    )
    expect(result.current.prod).toBe(false)
    expect(result.current.needsToken).toBe(true)
    expect(result.current.tokenOk).toBe(false) // gated even off-prod

    act(() => {
      result.current.setTyped('engine-a')
    })
    expect(result.current.tokenOk).toBe(true)
  })

  it('needsToken is false on prod when the action has no token gate at all', () => {
    const { result } = renderHook(() =>
      useProdGuard({ reasonRule: { required: true, minLength: 10 }, environment: 'prod' }),
    )
    expect(result.current.prod).toBe(true)
    expect(result.current.needsToken).toBe(false)
    expect(result.current.tokenOk).toBe(true)
  })

  it('on prod with a token gate, tokenOk requires an EXACT match', () => {
    const { result } = renderHook(() =>
      useProdGuard({
        reasonRule: { required: true, minLength: 10 },
        environment: 'PROD', // case-insensitive
        expectedToken: 'order-4711',
      }),
    )
    expect(result.current.needsToken).toBe(true)
    expect(result.current.tokenOk).toBe(false)

    act(() => {
      result.current.setTyped('order-471') // one char short
    })
    expect(result.current.tokenOk).toBe(false)

    act(() => {
      result.current.setTyped('order-4711')
    })
    expect(result.current.tokenOk).toBe(true)
  })

  it('ticket state is independent and untouched by the reason/token gates', () => {
    const { result } = renderHook(() =>
      useProdGuard({ reasonRule: { required: false, minLength: 10 } }),
    )
    act(() => {
      result.current.setTicket('OPS-42')
    })
    expect(result.current.ticket).toBe('OPS-42')
    expect(result.current.reasonOk).toBe(true) // unaffected
  })
})

describe('businessKeyOrInstanceToken', () => {
  it('prefers the business key when present', () => {
    expect(businessKeyOrInstanceToken('order-4711', 'pi-1')).toEqual({
      expectedToken: 'order-4711',
      tokenName: 'business key',
    })
  })

  it('falls back to the instance id when the business key is absent or blank', () => {
    expect(businessKeyOrInstanceToken(undefined, 'pi-1')).toEqual({
      expectedToken: 'pi-1',
      tokenName: 'instance id',
    })
    expect(businessKeyOrInstanceToken('', 'pi-1')).toEqual({
      expectedToken: 'pi-1',
      tokenName: 'instance id',
    })
  })
})
