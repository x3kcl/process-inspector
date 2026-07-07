import { describe, expect, it } from 'vitest'
import type { EngineDto, SearchRequest } from '../api/model'
import { criteriaChips, enginesInScope, prodConfirmToken } from './FilterBulkModal'

const engine = (id: string, environment: string): EngineDto => ({ id, environment })

const criteria = (partial: Partial<SearchRequest>): SearchRequest => ({ ...partial })

describe('enginesInScope', () => {
  const all = [engine('a', 'dev'), engine('b', 'prod')]

  it('narrows to the named engines', () => {
    expect(enginesInScope(criteria({ engineIds: ['b'] }), all)).toEqual([engine('b', 'prod')])
  })

  it('an unset engine filter means every engine', () => {
    expect(enginesInScope(criteria({}), all)).toEqual(all)
  })
})

describe('prodConfirmToken (stable identity, never a raceable count)', () => {
  it('prefers the definition key', () => {
    expect(
      prodConfirmToken(criteria({ processDefinitionKey: 'payment' }), [engine('b', 'prod')]),
    ).toBe('payment')
  })

  it('falls back to the single prod engine id', () => {
    expect(prodConfirmToken(criteria({}), [engine('b', 'prod')])).toBe('b')
  })

  it('multiple prod engines without a definition key attest ALL', () => {
    expect(prodConfirmToken(criteria({}), [engine('b', 'prod'), engine('c', 'prod')])).toBe('ALL')
  })
})

describe('criteriaChips', () => {
  it('restates the scope the operator attests to', () => {
    const chips = criteriaChips(
      criteria({
        statuses: ['FAILED'],
        processDefinitionKey: 'payment',
        definitionVersion: 3,
        engineIds: ['a'],
        errorText: 'timeout',
      }),
    )
    expect(chips).toContain('FAILED')
    expect(chips).toContain('payment v3')
    expect(chips).toContain('engines: a')
    expect(chips).toContain('error contains "timeout"')
  })
})
