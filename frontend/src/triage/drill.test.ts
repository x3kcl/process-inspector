import { describe, expect, it } from 'vitest'
import { decodeSearch } from '../search/urlState'
import {
  definitionDrillParams,
  engineDrillParams,
  groupDefinitionCounts,
  groupDrillParams,
  splitDefinitionCount,
  statusDrillParams,
} from './drill'

describe('splitDefinitionCount', () => {
  it('splits the normative defKey:vN form', () => {
    expect(splitDefinitionCount('orderFulfilment:v47')).toEqual({
      definitionKey: 'orderFulfilment',
      version: 'v47',
    })
  })

  it('keeps colons that are part of the key when the suffix is not vN', () => {
    expect(splitDefinitionCount('ns:orderFulfilment')).toEqual({
      definitionKey: 'ns:orderFulfilment',
      version: '',
    })
  })

  it('splits on the LAST colon so namespaced keys survive', () => {
    expect(splitDefinitionCount('ns:order:v3')).toEqual({
      definitionKey: 'ns:order',
      version: 'v3',
    })
  })
})

describe('drill params round-trip through the M2b URL codec', () => {
  it('definition drill carries engine + definition + failure lanes', () => {
    const request = decodeSearch(
      new URLSearchParams(definitionDrillParams('engine-a', 'orderFulfilment')),
    )
    expect(request).not.toBeNull()
    expect(request?.engineIds).toEqual(['engine-a'])
    expect(request?.processDefinitionKey).toBe('orderFulfilment')
    expect(request?.statuses).toEqual(['FAILED', 'RETRYING'])
    expect(request?.sortBy).toBe('failureTime')
  })

  it('engine drill carries engine + failure lanes only', () => {
    const request = decodeSearch(new URLSearchParams(engineDrillParams('engine-b')))
    expect(request?.engineIds).toEqual(['engine-b'])
    expect(request?.processDefinitionKey).toBeUndefined()
  })

  it('group drill fans out over every engine the group was observed on, sorted', () => {
    const request = decodeSearch(
      new URLSearchParams(
        groupDrillParams({
          countsByEngine: { 'engine-b': { 'k:v1': 2 }, 'engine-a': { 'k:v1': 1 } },
        }),
      ),
    )
    expect(request?.engineIds).toEqual(['engine-a', 'engine-b'])
  })

  it('group drill carries the error-class signature so the grid scopes to ONE class', () => {
    const request = decodeSearch(
      new URLSearchParams(
        groupDrillParams({
          signatureHash: 'f'.repeat(64),
          countsByEngine: { 'engine-a': { 'k:v1': 1 } },
        }),
      ),
    )
    expect(request?.signatureHash).toBe('f'.repeat(64))
  })

  it('status tile drill is the bare status filter', () => {
    const request = decodeSearch(new URLSearchParams(statusDrillParams('SUSPENDED')))
    expect(request?.statuses).toEqual(['SUSPENDED'])
    expect(request?.engineIds).toBeUndefined()
  })
})

describe('groupDefinitionCounts', () => {
  it('folds versions under their definition, newest version first, zero-fill kept', () => {
    const rows = groupDefinitionCounts({
      'orderFulfilment:v46': 0,
      'orderFulfilment:v47': 312,
      'payment:v3': 5,
    })
    expect(rows.map((r) => r.definitionKey)).toEqual(['orderFulfilment', 'payment'])
    expect(rows[0]?.total).toBe(312)
    expect(rows[0]?.versions).toEqual([
      { version: 'v47', count: 312 },
      { version: 'v46', count: 0 },
    ])
  })

  it('orders definitions by failure volume, ties alphabetical', () => {
    const rows = groupDefinitionCounts({ 'b:v1': 2, 'a:v1': 2, 'c:v1': 9 })
    expect(rows.map((r) => r.definitionKey)).toEqual(['c', 'a', 'b'])
  })

  it('keeps a versionless key readable', () => {
    const rows = groupDefinitionCounts({ someKey: 4 })
    expect(rows[0]?.versions).toEqual([{ version: '', count: 4 }])
  })
})
