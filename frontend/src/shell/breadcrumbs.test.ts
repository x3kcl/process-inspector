import { describe, expect, it } from 'vitest'
import {
  adminEngineCrumbs,
  caseCrumbs,
  definitionVersionCrumbs,
  inspectCrumbs,
} from './breadcrumbs'

describe('inspectCrumbs', () => {
  it('links engine, definition and version, and leaves the instance unlinked', () => {
    const crumbs = inspectCrumbs({
      engineId: 'eng1',
      engineName: 'Payments DEV',
      definitionKey: 'payment',
      definitionVersion: 3,
      instanceId: 'p-1',
    })
    expect(crumbs.map((crumb) => crumb.label)).toEqual(['Payments DEV', 'payment', 'v3', 'p-1'])
    expect(crumbs[0]?.to).toBe('/search?engines=eng1')
    expect(crumbs[1]?.to).toBe('/definitions/eng1/payment/versions')
    expect(crumbs[2]?.to).toContain('definitionKey=payment')
    expect(crumbs[2]?.to).toContain('version=3')
    expect(crumbs[3]?.to).toBeUndefined()
  })

  it('uses the URL ids before the payload arrives', () => {
    const crumbs = inspectCrumbs({ engineId: 'eng1', instanceId: 'p-1' })
    expect(crumbs.map((crumb) => crumb.label)).toEqual(['eng1', 'p-1'])
  })
})

describe('other trails', () => {
  it('case trail is engine then the case', () => {
    expect(
      caseCrumbs({ engineId: 'eng1', engineName: 'Payments', caseLabel: 'dispute' }).map(
        (c) => c.label,
      ),
    ).toEqual(['Payments', 'dispute'])
  })

  it('definition versions trail stops on the key', () => {
    const crumbs = definitionVersionCrumbs({ engineId: 'eng1', definitionKey: 'payment' })
    expect(crumbs[1]).toEqual({ label: 'payment' })
  })

  it('admin trail is Admin then Engines', () => {
    expect(adminEngineCrumbs()).toEqual([
      { label: 'Admin', to: '/admin/engines' },
      { label: 'Engines' },
    ])
  })
})
