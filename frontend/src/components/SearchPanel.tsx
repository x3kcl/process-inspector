import { useState } from 'react'
import type { EngineInfo, InstanceStatus, SearchRequest, VariableFilter } from '../types'

const ALL_STATUSES: InstanceStatus[] = ['ACTIVE', 'SUSPENDED', 'COMPLETED', 'FAILED']

interface Props {
  engines: EngineInfo[]
  busy: boolean
  onSearch: (req: SearchRequest) => void
}

/**
 * Spec §3.A — OR within a category (engine checkboxes, status checkboxes),
 * AND between categories (all filled filters combine).
 */
export function SearchPanel({ engines, busy, onSearch }: Props) {
  const [engineIds, setEngineIds] = useState<string[]>([])
  const [statuses, setStatuses] = useState<InstanceStatus[]>([])
  const [definitionKey, setDefinitionKey] = useState('')
  const [businessKey, setBusinessKey] = useState('')
  const [startedAfter, setStartedAfter] = useState('')
  const [startedBefore, setStartedBefore] = useState('')
  const [variables, setVariables] = useState<VariableFilter[]>([])

  const toggle = <T,>(list: T[], item: T): T[] =>
    list.includes(item) ? list.filter((x) => x !== item) : [...list, item]

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    onSearch({
      engineIds,
      statuses,
      processDefinitionKey: definitionKey || undefined,
      businessKey: businessKey || undefined,
      startedAfter: startedAfter ? new Date(startedAfter).toISOString() : undefined,
      startedBefore: startedBefore ? new Date(startedBefore).toISOString() : undefined,
      variables: variables.filter((v) => v.name),
    })
  }

  return (
    <form className="search-panel" onSubmit={submit}>
      <h2>Search</h2>

      <fieldset>
        <legend>Engines (all when none checked)</legend>
        {engines.map((e) => (
          <label key={e.id} className="check-row">
            <input
              type="checkbox"
              checked={engineIds.includes(e.id)}
              onChange={() => setEngineIds(toggle(engineIds, e.id))}
            />
            <span className="engine-dot" style={{ background: e.color }} />
            {e.name}
            <span className={e.reachable ? 'health ok' : 'health down'}>
              {e.reachable ? '●' : '○'}
            </span>
          </label>
        ))}
      </fieldset>

      <fieldset>
        <legend>Status</legend>
        {ALL_STATUSES.map((s) => (
          <label key={s} className="check-row">
            <input
              type="checkbox"
              checked={statuses.includes(s)}
              onChange={() => setStatuses(toggle(statuses, s))}
            />
            {s === 'FAILED' ? 'Failed / Error (dead-letter)' : s.toLowerCase()}
          </label>
        ))}
      </fieldset>

      <fieldset>
        <legend>Process</legend>
        <label>
          Definition key
          <input value={definitionKey} onChange={(e) => setDefinitionKey(e.target.value)} placeholder="orderFulfilment" />
        </label>
        <label>
          Business key
          <input value={businessKey} onChange={(e) => setBusinessKey(e.target.value)} placeholder="ORDER-4711" />
        </label>
      </fieldset>

      <fieldset>
        <legend>Started</legend>
        <label>
          After
          <input type="datetime-local" value={startedAfter} onChange={(e) => setStartedAfter(e.target.value)} />
        </label>
        <label>
          Before
          <input type="datetime-local" value={startedBefore} onChange={(e) => setStartedBefore(e.target.value)} />
        </label>
      </fieldset>

      <fieldset>
        <legend>Variables</legend>
        {variables.map((v, i) => (
          <div key={i} className="var-row">
            <input
              placeholder="name"
              value={v.name}
              onChange={(e) => setVariables(variables.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))}
            />
            <select
              value={v.operation}
              onChange={(e) => setVariables(variables.map((x, j) => (j === i ? { ...x, operation: e.target.value } : x)))}
            >
              <option value="equals">=</option>
              <option value="like">like</option>
              <option value="greaterThan">&gt;</option>
              <option value="lessThan">&lt;</option>
            </select>
            <input
              placeholder="value"
              value={v.value}
              onChange={(e) => setVariables(variables.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))}
            />
            <button type="button" onClick={() => setVariables(variables.filter((_, j) => j !== i))}>✕</button>
          </div>
        ))}
        <button type="button" onClick={() => setVariables([...variables, { name: '', value: '', operation: 'equals' }])}>
          + variable filter
        </button>
      </fieldset>

      <button type="submit" className="primary" disabled={busy}>
        {busy ? 'Searching…' : 'Search'}
      </button>
    </form>
  )
}
