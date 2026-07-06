import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import type { ResolveMatch, ResolveResponse } from '../api/model'
import { resolveQuery } from '../api/queries'
import { useEngines } from '../api/useEngines'
import { StatusChip } from '../components/StatusChip'
import { encodeSearch } from '../search/urlState'
import { classifyOmniboxInput, decideResolveNavigation, summarizeReachability } from './omnibox'

/**
 * The global "paste anything" input, pinned in the header on every stage (SPEC §4).
 * engine:id composites open Stage 2 directly; everything else goes through GET
 * /api/resolve (R-SEM-04): one exact ID match navigates, business keys land on a
 * pre-filtered search, ambiguity renders an explicit picker — never a guess.
 */
export function Omnibox() {
  const navigate = useNavigate()
  const engines = useEngines()
  const [value, setValue] = useState('')
  const [panel, setPanel] = useState<ResolveResponse>()

  const resolver = useMutation({
    mutationFn: resolveQuery,
    onSuccess: (response) => {
      const decision = decideResolveNavigation(response)
      switch (decision.kind) {
        case 'navigate':
          finish(`/inspect/${decision.engineId}/${encodeURIComponent(decision.processInstanceId)}`)
          return
        case 'search-business-key':
          finish(`/search?${encodeSearch({ businessKey: decision.businessKey }).toString()}`)
          return
        default:
          // disambiguate & not-found both render the panel — with the honesty line.
          setPanel(response)
      }
    },
  })

  const finish = (to: string) => {
    setPanel(undefined)
    setValue('')
    void navigate(to)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const engineIds = (engines.data ?? [])
      .map((engine) => engine.id)
      .filter((id): id is string => id !== undefined)
    const target = classifyOmniboxInput(value, engineIds)
    if (target === null) return
    if (target.kind === 'inspect') {
      finish(`/inspect/${target.engineId}/${encodeURIComponent(target.instanceId)}`)
      return
    }
    setPanel(undefined)
    resolver.mutate(target.query)
  }

  return (
    <form className="omnibox" onSubmit={submit} role="search">
      <input
        value={value}
        onChange={(event) => {
          setValue(event.target.value)
          setPanel(undefined)
        }}
        placeholder="paste an instance / task / job ID or a business key…"
        aria-label="Resolve any pasted ID across engines, or search a business key"
        title={
          'Paste a composite "engine:id", a raw process/execution/task/job ID, or a ' +
          'business key — resolved across all reachable engines.'
        }
      />
      {resolver.isPending && <span className="omnibox-pending">resolving…</span>}
      {resolver.isError && (
        <div className="omnibox-panel error-banner" role="alert">
          Resolve failed: {resolver.error.message}
        </div>
      )}
      {panel !== undefined && (
        <ResolvePanel
          response={panel}
          onPick={(match) => {
            if (match.engineId !== undefined && match.processInstanceId !== undefined) {
              finish(`/inspect/${match.engineId}/${encodeURIComponent(match.processInstanceId)}`)
            }
          }}
          onSearchKey={(businessKey) => {
            finish(`/search?${encodeSearch({ businessKey }).toString()}`)
          }}
        />
      )}
    </form>
  )
}

const KIND_LABELS: Record<string, string> = {
  PROCESS_INSTANCE: 'process instance',
  EXECUTION: 'execution',
  TASK: 'task',
  JOB: 'job',
  BUSINESS_KEY: 'business key',
}

/** The disambiguation dropdown: kind + engine + status per match, honesty line below. */
function ResolvePanel({
  response,
  onPick,
  onSearchKey,
}: {
  response: ResolveResponse
  onPick: (match: ResolveMatch) => void
  onSearchKey: (businessKey: string) => void
}) {
  const matches = response.matches ?? []
  const reachability = summarizeReachability(response)
  return (
    <div className="omnibox-panel" role="listbox" aria-label="Resolve results">
      {matches.length === 0 ? (
        <p className="zero-state">
          “{response.query}” was not found on any reachable engine
          {reachability.unreachable.length > 0 &&
            ' — but some engines could not be searched (below)'}
          .
        </p>
      ) : (
        <ul className="omnibox-matches">
          {matches.map((match, index) => (
            <li key={`${match.compositeId ?? ''}:${match.kind ?? ''}:${String(index)}`}>
              <button
                type="button"
                className="omnibox-match"
                onClick={() => {
                  if (match.kind === 'BUSINESS_KEY') {
                    onSearchKey(match.businessKey ?? response.query ?? '')
                  } else {
                    onPick(match)
                  }
                }}
              >
                <span className="match-kind">{KIND_LABELS[match.kind ?? ''] ?? match.kind}</span>
                <span className="engine-name">{match.engineId}</span>
                <code className="composite-id">{match.compositeId}</code>
                <StatusChip status={match.status} flags={match.flags} />
                {match.businessKey !== undefined && (
                  <code className="hier-key">{match.businessKey}</code>
                )}
                {match.definitionKey !== undefined && (
                  <span className="value-muted">
                    {match.definitionKey}
                    {match.definitionVersion !== undefined &&
                      ` v${String(match.definitionVersion)}`}
                  </span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
      <p className="omnibox-reachability">
        resolved against {reachability.reached} of {reachability.total} engines
        {reachability.unreachable.map((probe) => (
          <span key={probe.engineId} className="health-down">
            {' '}
            · {probe.engineId} unreachable ({probe.error})
          </span>
        ))}
      </p>
    </div>
  )
}
