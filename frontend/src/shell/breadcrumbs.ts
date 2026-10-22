// URL-derived trail (#537). Labels may come from the loaded payload; the links do
// not. Search uses the `engines` query param — that is what encodeSearch writes.

import { encodeSearch } from '../search/urlState'

export interface Crumb {
  label: string
  /** Absent on the current page so a click cannot drop ?tab=. */
  to?: string
}

function engineSearch(engineId: string): string {
  return `/search?${encodeSearch({ engineIds: [engineId] }).toString()}`
}

function versionSearch(engineId: string, definitionKey: string, definitionVersion: number): string {
  return `/search?${encodeSearch({
    engineIds: [engineId],
    processDefinitionKey: definitionKey,
    definitionVersion,
  }).toString()}`
}

export function inspectCrumbs(input: {
  engineId: string
  engineName?: string
  definitionKey?: string
  definitionVersion?: number
  instanceId: string
}): Crumb[] {
  const crumbs: Crumb[] = [
    {
      label: input.engineName && input.engineName !== '' ? input.engineName : input.engineId,
      to: engineSearch(input.engineId),
    },
  ]
  if (input.definitionKey !== undefined && input.definitionKey !== '') {
    crumbs.push({
      label: input.definitionKey,
      to: `/definitions/${encodeURIComponent(input.engineId)}/${encodeURIComponent(input.definitionKey)}/versions`,
    })
    if (input.definitionVersion !== undefined) {
      crumbs.push({
        label: `v${String(input.definitionVersion)}`,
        to: versionSearch(input.engineId, input.definitionKey, input.definitionVersion),
      })
    }
  }
  crumbs.push({ label: input.instanceId })
  return crumbs
}

export function caseCrumbs(input: {
  engineId: string
  engineName?: string
  caseLabel: string
}): Crumb[] {
  return [
    {
      label: input.engineName && input.engineName !== '' ? input.engineName : input.engineId,
      to: engineSearch(input.engineId),
    },
    { label: input.caseLabel },
  ]
}

export function definitionVersionCrumbs(input: {
  engineId: string
  engineName?: string
  definitionKey: string
}): Crumb[] {
  return [
    {
      label: input.engineName && input.engineName !== '' ? input.engineName : input.engineId,
      to: engineSearch(input.engineId),
    },
    { label: input.definitionKey },
  ]
}

export function adminEngineCrumbs(): Crumb[] {
  return [{ label: 'Admin', to: '/admin/engines' }, { label: 'Engines' }]
}
