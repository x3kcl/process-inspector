// Dual-canvas click-to-map (#498): source definition left, target right, both read-only
// DiagramCanvas viewers. A click selects the source/target — the SAME selection the pair
// list's "pair source"/"pair target" selects hold, so canvas and selects stay in sync and
// "Add to map" feeds the same pair list and fan rules. Keyboard users keep the table mode.
// The bpmn.io watermark on both viewers is left exactly as rendered (R-GOV-05).
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { api, ApiError } from '../api/client'
import type { components } from '../api/schema'
import { DiagramCanvas } from '../inspect/DiagramCanvas'
import type { DiagramMarkers } from '../inspect/DiagramCanvas'
import { findingMarkerIds, mappedIds } from './mappingCanvasModel'
import type { FindingLike } from './mappingCanvasModel'
import type { ActivityPair } from './mappingRules'

type Xml = components['schemas']['DefinitionXmlResponse']

export interface MappingDiagrams {
  engineId: string
  fromDefinitionId: string
  toDefinitionId: string
}

interface Props extends MappingDiagrams {
  sourceIds: readonly string[]
  targetIds: readonly string[]
  findings: readonly FindingLike[]
  pairs: readonly ActivityPair[]
  selectedFrom: string
  selectedTo: string
  onSelectFrom: (id: string) => void
  onSelectTo: (id: string) => void
}

function split(key: string): string[] {
  return key === '' ? [] : key.split('\n')
}

function useDefinitionXml(engineId: string, definitionId: string) {
  return useQuery({
    // Same key as the definition page, so a diagram already seen there is not refetched.
    queryKey: ['definition-xml', engineId, definitionId],
    enabled: definitionId !== '',
    queryFn: async (): Promise<Xml> => {
      const { data, error, response } = await api.GET(
        '/api/definitions/{engineId}/{definitionId}/xml',
        { params: { path: { engineId, definitionId } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
}

function Pane({
  label,
  xml,
  failed,
  markers,
  selected,
  onSelect,
}: {
  label: string
  xml: string | undefined
  failed: boolean
  markers: DiagramMarkers
  selected: string
  onSelect: (id: string) => void
}) {
  return (
    <section className="mapping-canvas" aria-label={label}>
      <p className="mapping-canvas-label">{label}</p>
      {failed && <p className="strip-note">The diagram could not be loaded — use the table.</p>}
      {!failed && (xml === undefined || xml === '') && <p className="zero-state">Loading…</p>}
      {xml !== undefined && xml !== '' && (
        <DiagramCanvas
          xml={xml}
          markers={markers}
          onSelectActivity={onSelect}
          selectedActivityId={selected === '' ? undefined : selected}
        />
      )}
    </section>
  )
}

export function MappingCanvases({
  engineId,
  fromDefinitionId,
  toDefinitionId,
  sourceIds,
  targetIds,
  findings,
  pairs,
  selectedFrom,
  selectedTo,
  onSelectFrom,
  onSelectTo,
}: Props) {
  const fromXml = useDefinitionXml(engineId, fromDefinitionId)
  const toXml = useDefinitionXml(engineId, toDefinitionId)

  // DiagramCanvas re-imports on every new markers reference, and the modal hands in fresh
  // finding/pair arrays on each render (typing a reason re-renders it) — so the markers are
  // memoized on their CONTENT, joined to strings, not on the array references.
  const mapped = mappedIds(pairs, selectedFrom)
  const marks = findingMarkerIds(findings)
  const blockerKey = marks.blocker.join('\n')
  const warningKey = marks.warning.join('\n')
  const sourcesKey = mapped.sources.join('\n')
  const targetsKey = mapped.targets.join('\n')
  const sourceMarkers = useMemo<DiagramMarkers>(
    () => ({
      activeActivityIds: [],
      deadLetterActivityIds: [],
      blockerActivityIds: split(blockerKey),
      warningActivityIds: split(warningKey),
      mappedActivityIds: split(sourcesKey),
    }),
    [blockerKey, warningKey, sourcesKey],
  )
  const targetMarkers = useMemo<DiagramMarkers>(
    () => ({
      activeActivityIds: [],
      deadLetterActivityIds: [],
      mappedActivityIds: split(targetsKey),
    }),
    [targetsKey],
  )

  return (
    <div className="mapping-canvases">
      <p className="strip-note">
        Click a flagged activity on the left, then its target on the right, then “Add to map”. ✖
        marks a pre-check blocker, ! a warning, ↦ an activity already mapped.
      </p>
      <div className="mapping-canvas-pair">
        <Pane
          label="Source diagram (current version)"
          xml={fromXml.data?.xml}
          failed={fromXml.isError}
          markers={sourceMarkers}
          selected={selectedFrom}
          onSelect={(id) => {
            // Only activities that need a mapping are pickable; a click on a flow, the
            // process background or an auto-mapped activity changes nothing.
            if (sourceIds.includes(id)) onSelectFrom(id)
          }}
        />
        <Pane
          label="Target diagram (new version)"
          xml={toXml.data?.xml}
          failed={toXml.isError}
          markers={targetMarkers}
          selected={selectedTo}
          onSelect={(id) => {
            if (targetIds.includes(id)) onSelectTo(id)
          }}
        />
      </div>
    </div>
  )
}
