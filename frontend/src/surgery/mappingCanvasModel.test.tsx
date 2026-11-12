// @vitest-environment jsdom
// Issue #498: dual-canvas click-to-map. bpmn-js needs a real DOM, so the viewer is stubbed
// with buttons that report an activity click the way DiagramCanvas does; the canvases
// themselves are covered in Playwright (e2e/migrate.spec.ts).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DiagramMarkers } from '../inspect/DiagramCanvas'
import { MappingEditor } from './MappingEditor'
import { findingMarkerIds, mappedIds, tryAddPair } from './mappingCanvasModel'
import { MANY_TO_MANY_REASON, resetToGenerated } from './mappingRules'
import type { ActivityPair } from './mappingRules'

const lastMarkers: Record<string, DiagramMarkers> = {}

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: {
      GET: (_path: string, init: { params: { path: { definitionId: string } } }) =>
        Promise.resolve({
          data: { xml: `<xml id="${init.params.path.definitionId}"/>` },
          response: { status: 200 },
        }),
    },
  }
})

vi.mock('../inspect/DiagramCanvas', () => ({
  DiagramCanvas: ({
    xml,
    markers,
    onSelectActivity,
  }: {
    xml: string
    markers: DiagramMarkers
    onSelectActivity?: (id: string) => void
  }) => {
    const side = xml.includes('v1') ? 'source' : 'target'
    lastMarkers[side] = markers
    return (
      <div>
        {['a', 'b', 'flow1', 'x', 'y'].map((id) => (
          <button
            key={id}
            type="button"
            onClick={() => {
              onSelectActivity?.(id)
            }}
          >
            {`${side} canvas ${id}`}
          </button>
        ))}
      </div>
    )
  },
}))

afterEach(() => {
  cleanup()
})

const pair = (fromActivityId: string, toActivityId: string): ActivityPair => ({
  fromActivityId,
  toActivityId,
})

describe('tryAddPair (#498)', () => {
  it('allows fan-out and fan-in', () => {
    const out = tryAddPair([pair('a', 'x')], 'a', 'y')
    expect(out).toEqual({ ok: true, pairs: [pair('a', 'x'), pair('a', 'y')] })
    const fanIn = tryAddPair([pair('a', 'x')], 'b', 'x')
    expect(fanIn).toEqual({ ok: true, pairs: [pair('a', 'x'), pair('b', 'x')] })
  })

  it('refuses the pair that would make many-to-many and keeps the list', () => {
    expect(tryAddPair([pair('a', 'x'), pair('a', 'y')], 'b', 'x')).toEqual({
      ok: false,
      reason: MANY_TO_MANY_REASON,
    })
  })
})

describe('findingMarkerIds / mappedIds (#498)', () => {
  it('splits blockers and warnings; a blocker wins on the same id', () => {
    expect(
      findingMarkerIds([
        { activityId: 'a', severity: 'BLOCKER_ADVICE' },
        { activityId: 'a', severity: 'WARNING' },
        { activityId: 'b', severity: 'WARNING' },
        { activityId: 'c', severity: 'INFO' },
        { activityId: null, severity: 'BLOCKER_ADVICE' },
      ]),
    ).toEqual({ blocker: ['a'], warning: ['b'] })
  })

  it('marks only the selected source’s targets when a source is selected', () => {
    const pairs = [pair('a', 'x'), pair('b', 'y')]
    expect(mappedIds(pairs, '')).toEqual({ sources: ['a', 'b'], targets: ['x', 'y'] })
    expect(mappedIds(pairs, 'a')).toEqual({ sources: ['a', 'b'], targets: ['x'] })
  })
})

function Harness() {
  const [pairs, setPairs] = useState<ActivityPair[]>([])
  const [expertText, setExpertText] = useState<string | null>(null)
  return (
    <QueryClientProvider client={new QueryClient()}>
      <MappingEditor
        sources={[{ id: 'a' }, { id: 'b' }]}
        targets={[{ id: 'x' }, { id: 'y' }]}
        findings={[{ activityId: 'a', severity: 'BLOCKER_ADVICE' }]}
        pairs={pairs}
        onPairsChange={setPairs}
        expertText={expertText}
        onExpertTextChange={setExpertText}
        diagrams={{ engineId: 'eng1', fromDefinitionId: 'p:v1', toDefinitionId: 'p:v2' }}
      />
    </QueryClientProvider>
  )
}

describe('MappingEditor diagrams mode (#498)', () => {
  it('has no Diagrams button without definitions', () => {
    render(
      <MappingEditor
        sources={[{ id: 'a' }]}
        targets={[{ id: 'x' }]}
        findings={[]}
        pairs={[]}
        onPairsChange={() => undefined}
        expertText={null}
        onExpertTextChange={() => undefined}
      />,
    )
    expect(screen.queryByRole('button', { name: 'Diagrams' })).toBeNull()
  })

  it('click-to-map feeds the pair list, syncs the selects, and refuses many-to-many', async () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Diagrams' }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'source canvas a' })).toBeTruthy()
    })
    // The blocker finding is overlaid on the source canvas.
    expect(lastMarkers.source.blockerActivityIds).toEqual(['a'])

    const map = (from: string, to: string) => {
      fireEvent.click(screen.getByRole('button', { name: `source canvas ${from}` }))
      fireEvent.click(screen.getByRole('button', { name: `target canvas ${to}` }))
      fireEvent.click(screen.getByRole('button', { name: 'Add to map' }))
    }

    // A click on a sequence flow is not a pickable source.
    fireEvent.click(screen.getByRole('button', { name: 'source canvas flow1' }))
    expect(screen.getByRole('combobox', { name: 'pair source' })).toHaveProperty('value', '')
    // Canvas selection drives the same selects the keyboard path uses.
    fireEvent.click(screen.getByRole('button', { name: 'source canvas a' }))
    expect(screen.getByRole('combobox', { name: 'pair source' })).toHaveProperty('value', 'a')

    map('a', 'x')
    map('a', 'y')
    expect(screen.getByRole('button', { name: 'Remove a to x' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Remove a to y' })).toBeTruthy()
    expect(lastMarkers.source.mappedActivityIds).toEqual(['a'])

    map('b', 'x')
    expect(screen.getByRole('status').textContent).toContain(MANY_TO_MANY_REASON)
    expect(screen.queryByRole('button', { name: 'Remove b to x' })).toBeNull()

    // The expert pane shows exactly the generated fan-out the BFF will be proposed.
    fireEvent.click(screen.getByRole('button', { name: 'Expert' }))
    expect(screen.getByRole('textbox', { name: 'mapping json' })).toHaveProperty(
      'value',
      resetToGenerated([pair('a', 'x'), pair('a', 'y')]),
    )
  })
})
