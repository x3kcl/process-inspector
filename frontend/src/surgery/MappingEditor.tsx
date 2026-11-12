// Mapping editor for MigrateModal (#498): table (source → target select), pair list,
// and expert JSON. Keyboard uses the selects and buttons. With `diagrams` set, a Diagrams
// mode adds the dual-canvas click-to-map (MappingCanvases) over the same pair list.
import { useState } from 'react'
import {
  addPair,
  compileMappings,
  expertIsDirty,
  parseMappingJson,
  resetToGenerated,
} from './mappingRules'
import type { ActivityPair } from './mappingRules'
import { MappingCanvases } from './MappingCanvases'
import type { MappingDiagrams } from './MappingCanvases'
import { tryAddPair } from './mappingCanvasModel'

export interface MappingEndpoint {
  id: string
  name?: string
  type?: string
}

export interface MappingFindingMark {
  activityId?: string
  severity?: string
  detail?: string
  code?: string
}

interface Props {
  sources: MappingEndpoint[]
  targets: MappingEndpoint[]
  findings: readonly MappingFindingMark[]
  pairs: readonly ActivityPair[]
  onPairsChange: (pairs: ActivityPair[]) => void
  expertText: string | null
  onExpertTextChange: (text: string | null) => void
  /** Source/target definitions for the Diagrams mode; absent = no Diagrams button. */
  diagrams?: MappingDiagrams
}

function endpointLabel(endpoint: MappingEndpoint): string {
  const name = endpoint.name
  const named = name !== undefined && name !== '' && name !== endpoint.id
  const base = named ? `${name} (${endpoint.id})` : endpoint.id
  const type = endpoint.type
  if (type === undefined || type === '') return base
  return `${base} · ${type}`
}

function findingWord(findings: readonly MappingFindingMark[], activityId: string): string | null {
  const mine = findings.filter((finding) => finding.activityId === activityId)
  if (mine.some((finding) => finding.severity === 'BLOCKER_ADVICE')) return 'blocker'
  if (mine.some((finding) => finding.severity === 'WARNING')) return 'warning'
  if (mine.some((finding) => finding.severity === 'INFO')) return 'info'
  return null
}

function chosenTargets(pairs: readonly ActivityPair[], fromId: string): string[] {
  const targets: string[] = []
  for (const pair of pairs) {
    if (pair.fromActivityId === fromId && !targets.includes(pair.toActivityId)) {
      targets.push(pair.toActivityId)
    }
  }
  return targets
}

export function MappingEditor({
  sources,
  targets,
  findings,
  pairs,
  onPairsChange,
  expertText,
  onExpertTextChange,
  diagrams,
}: Props) {
  const [mode, setMode] = useState<'table' | 'diagram' | 'expert'>(
    expertText !== null ? 'expert' : 'table',
  )
  const [pendingDiscard, setPendingDiscard] = useState<'reset' | 'table' | 'diagram' | null>(null)
  const [extras, setExtras] = useState<Record<string, number>>({})
  const [pairFrom, setPairFrom] = useState('')
  const [pairTo, setPairTo] = useState('')
  const [refusal, setRefusal] = useState<string | null>(null)

  const generated = resetToGenerated(pairs)
  const shown = expertText ?? generated
  const submit = expertText === null ? compileMappings(pairs) : parseMappingJson(expertText)

  const slotsFor = (fromId: string): string[] => {
    const chosen = chosenTargets(pairs, fromId)
    const blanks = Array.from({ length: extras[fromId] ?? 0 }, () => '')
    return chosen.length === 0 ? ['', ...blanks] : [...chosen, ...blanks]
  }

  const onSlotChange = (fromId: string, index: number, value: string) => {
    const slots = slotsFor(fromId)
    const previous = slots[index] ?? ''
    const chosenCount = chosenTargets(pairs, fromId).length
    let next = pairs.filter(
      (pair) => pair.fromActivityId !== fromId || pair.toActivityId !== previous || previous === '',
    )
    if (previous !== '' && value === previous) return
    if (value !== '') next = addPair(next, fromId, value)
    onPairsChange([...next])
    const filledExtra = previous === '' && value !== '' && index >= Math.max(chosenCount, 1)
    if (filledExtra) {
      setExtras((current) => ({ ...current, [fromId]: Math.max(0, (current[fromId] ?? 0) - 1) }))
    }
  }

  const applyDiscard = (action: 'reset' | 'table' | 'diagram') => {
    if (expertText !== null) onExpertTextChange(null)
    setPendingDiscard(null)
    if (action !== 'reset') setMode(action)
  }

  const requestDiscard = (action: 'reset' | 'table' | 'diagram') => {
    if (expertIsDirty(generated, shown)) {
      setPendingDiscard(action)
      return
    }
    if (action !== 'reset') setMode(action)
    else if (expertText !== null) onExpertTextChange(null)
  }

  return (
    <div className="mapping-editor">
      <div className="modal-field">
        <button
          type="button"
          aria-pressed={mode === 'table'}
          onClick={() => {
            requestDiscard('table')
          }}
        >
          Table
        </button>{' '}
        {diagrams !== undefined && (
          <>
            <button
              type="button"
              aria-pressed={mode === 'diagram'}
              onClick={() => {
                requestDiscard('diagram')
              }}
            >
              Diagrams
            </button>{' '}
          </>
        )}
        <button
          type="button"
          aria-pressed={mode === 'expert'}
          onClick={() => {
            setMode('expert')
          }}
        >
          Expert
        </button>
      </div>

      {!submit.ok && (
        <p className="strip-note" role="alert">
          {submit.reason}
        </p>
      )}

      {mode === 'table' && sources.length === 0 && pairs.length === 0 && (
        <p className="strip-note">
          No activities to map by hand. Expert mode can still edit the mapping JSON.
        </p>
      )}

      {mode === 'table' && sources.length > 0 && (
        <table className="mapping-table">
          <tbody>
            {sources.map((source) => {
              const word = findingWord(findings, source.id)
              const slots = slotsFor(source.id)
              return (
                <tr key={source.id}>
                  <td>
                    <code>{source.id}</code>
                    {source.name !== undefined && source.name !== '' && <> {source.name}</>}
                    {word !== null && (
                      <>
                        {' '}
                        <span>{word}</span>
                      </>
                    )}
                  </td>
                  <td aria-hidden>→</td>
                  <td>
                    {slots.map((slot, index) => (
                      <select
                        key={`${source.id}-${String(index)}`}
                        aria-label={
                          index === 0
                            ? `target for ${source.id}`
                            : `additional target ${String(index + 1)} for ${source.id}`
                        }
                        value={slot}
                        onChange={(event) => {
                          onSlotChange(source.id, index, event.target.value)
                        }}
                      >
                        <option value="">choose target…</option>
                        {targets.map((target) => (
                          <option key={target.id} value={target.id}>
                            {endpointLabel(target)}
                          </option>
                        ))}
                      </select>
                    ))}
                    <button
                      type="button"
                      onClick={() => {
                        setExtras((current) => ({
                          ...current,
                          [source.id]: (current[source.id] ?? 0) + 1,
                        }))
                      }}
                    >
                      Add another target for {source.id}
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      {mode === 'diagram' && diagrams !== undefined && (
        <MappingCanvases
          {...diagrams}
          sourceIds={sources.map((source) => source.id)}
          targetIds={targets.map((target) => target.id)}
          findings={findings}
          pairs={pairs}
          selectedFrom={pairFrom}
          selectedTo={pairTo}
          onSelectFrom={(id) => {
            setPairFrom(id)
            setRefusal(null)
          }}
          onSelectTo={(id) => {
            setPairTo(id)
            setRefusal(null)
          }}
        />
      )}

      {mode !== 'expert' && (sources.length > 0 || pairs.length > 0) && (
        <div className="modal-field">
          <span>Pair list</span>
          <ul>
            {pairs.map((pair) => (
              <li key={`${pair.fromActivityId}-${pair.toActivityId}`}>
                <code>{pair.fromActivityId}</code> → <code>{pair.toActivityId}</code>{' '}
                <button
                  type="button"
                  onClick={() => {
                    onPairsChange(
                      pairs.filter(
                        (item) =>
                          item.fromActivityId !== pair.fromActivityId ||
                          item.toActivityId !== pair.toActivityId,
                      ),
                    )
                  }}
                >
                  Remove {pair.fromActivityId} to {pair.toActivityId}
                </button>
              </li>
            ))}
          </ul>
          {sources.length > 0 && (
            <>
              <select
                aria-label="pair source"
                value={pairFrom}
                onChange={(event) => {
                  setPairFrom(event.target.value)
                }}
              >
                <option value="">choose source…</option>
                {sources.map((source) => (
                  <option key={source.id} value={source.id}>
                    {endpointLabel(source)}
                  </option>
                ))}
              </select>{' '}
              <select
                aria-label="pair target"
                value={pairTo}
                onChange={(event) => {
                  setPairTo(event.target.value)
                }}
              >
                <option value="">choose target…</option>
                {targets.map((target) => (
                  <option key={target.id} value={target.id}>
                    {endpointLabel(target)}
                  </option>
                ))}
              </select>{' '}
              <button
                type="button"
                disabled={pairFrom === '' || pairTo === ''}
                onClick={() => {
                  const added = tryAddPair(pairs, pairFrom, pairTo)
                  if (!added.ok) {
                    setRefusal(added.reason)
                    return
                  }
                  setRefusal(null)
                  onPairsChange(added.pairs)
                  setPairFrom('')
                  setPairTo('')
                }}
              >
                Add to map
              </button>
              {refusal !== null && (
                <p className="strip-note" role="status">
                  Not added: {refusal}
                </p>
              )}
            </>
          )}
        </div>
      )}

      {mode === 'expert' && (
        <div className="modal-field">
          <span>Mapping JSON the next check submits</span>
          <textarea
            aria-label="mapping json"
            rows={10}
            spellCheck={false}
            value={shown}
            onChange={(event) => {
              onExpertTextChange(event.target.value)
            }}
          />
          <p className="strip-note">
            The BFF re-validates this array and rebuilds the engine activityMappings document.
          </p>
          <button
            type="button"
            onClick={() => {
              requestDiscard('reset')
            }}
          >
            Reset to generated
          </button>
          {pendingDiscard !== null && (
            <div>
              <p>Discard unsaved expert edits?</p>
              <button
                type="button"
                onClick={() => {
                  applyDiscard(pendingDiscard)
                }}
              >
                Discard
              </button>{' '}
              <button
                type="button"
                onClick={() => {
                  setPendingDiscard(null)
                }}
              >
                Keep editing
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
