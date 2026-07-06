import { describe, expect, it } from 'vitest'
import { classifyOmniboxInput } from './omnibox'

const ENGINES = ['engine-a', 'engine-b'] as const

describe('classifyOmniboxInput', () => {
  it('empty or whitespace input resolves to nothing', () => {
    expect(classifyOmniboxInput('', ENGINES)).toBeNull()
    expect(classifyOmniboxInput('   ', ENGINES)).toBeNull()
  })

  it('composite engine:id navigates to Stage 2', () => {
    expect(classifyOmniboxInput('engine-a:8f14e45f-ceea-4711', ENGINES)).toEqual({
      kind: 'inspect',
      engineId: 'engine-a',
      instanceId: '8f14e45f-ceea-4711',
    })
  })

  it('trims paste debris around the composite', () => {
    expect(classifyOmniboxInput('  engine-b: 42 ', ENGINES)).toEqual({
      kind: 'inspect',
      engineId: 'engine-b',
      instanceId: '42',
    })
  })

  it('a colon with an unknown prefix stays a business key', () => {
    expect(classifyOmniboxInput('order:4711', ENGINES)).toEqual({
      kind: 'business-key',
      businessKey: 'order:4711',
    })
  })

  it('a dangling composite (no id after the colon) stays a business key', () => {
    expect(classifyOmniboxInput('engine-a:', ENGINES)).toEqual({
      kind: 'business-key',
      businessKey: 'engine-a:',
    })
  })

  it('plain input is a business-key search (never an auto-navigate, R-SEM-04)', () => {
    expect(classifyOmniboxInput('order-4711', ENGINES)).toEqual({
      kind: 'business-key',
      businessKey: 'order-4711',
    })
  })
})
