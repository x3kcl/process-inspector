import { describe, expect, it } from 'vitest'
import { needsTypedToken, rowActions, toEnvironment } from './lifecycle'

describe('registry admin lifecycle logic', () => {
  it('offers the earned-trust actions per lifecycle', () => {
    expect(rowActions('draft')).toMatchObject({ probe: true, edit: true, enable: false })
    expect(rowActions('probed')).toMatchObject({ enable: true, disable: false })
    expect(rowActions('active')).toMatchObject({ disable: true, enable: false, remove: false })
    expect(rowActions('disabled')).toMatchObject({ enable: true, remove: true })
    // A tombstone offers ONLY purge — no probe/edit/enable on a removed engine.
    expect(rowActions('removed')).toEqual({
      probe: false,
      edit: false,
      enable: false,
      disable: false,
      remove: false,
      purge: true,
    })
  })

  it('requires a typed token for remove, purge, and prod read-write enable only', () => {
    expect(needsTypedToken('remove', 'dev', false)).toBe(true)
    expect(needsTypedToken('purge', 'dev', false)).toBe(true)
    expect(needsTypedToken('disable', 'prod', false)).toBe(false)
    // enable: only when prod AND read-write.
    expect(needsTypedToken('enable', 'prod', true)).toBe(true)
    expect(needsTypedToken('enable', 'prod', false)).toBe(false)
    expect(needsTypedToken('enable', 'dev', true)).toBe(false)
    expect(needsTypedToken('enable', 'test', true)).toBe(false)
  })

  it('maps the lowercase read DTO env to the uppercase write enum', () => {
    expect(toEnvironment('prod')).toBe('PROD')
    expect(toEnvironment('test')).toBe('TEST')
    expect(toEnvironment('dev')).toBe('DEV')
    expect(toEnvironment(undefined)).toBe('DEV')
    expect(toEnvironment('nonsense')).toBe('DEV')
  })
})
