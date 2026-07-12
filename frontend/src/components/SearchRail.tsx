import { useState } from 'react'
import type { FormEvent } from 'react'
import type { EngineDto, InstanceStatus, SearchRequest, SearchResponse } from '../api/model'
import { ALL_STATUSES } from '../api/model'
import { isInactiveLifecycle, lifecycleGloss } from '../lib/enginePolicy'
import { formatCount } from '../lib/format'
import { SaveViewControl } from '../views/SaveViewControl'
import { CopyButton } from './CopyButton'

interface Props {
  engines: EngineDto[]
  /** Decoded from the URL — the form is only an editor for it. */
  initial: SearchRequest | null
  /** The raw URL search string when one is applied — what "Save current view" names. */
  currentSearch: string | null
  response: SearchResponse | undefined
  busy: boolean
  collapsed: boolean
  onToggle: () => void
  onSubmit: (request: SearchRequest) => void
}

const STATUS_LABELS: Record<InstanceStatus, string> = {
  ACTIVE: 'Active',
  SUSPENDED: 'Suspended',
  COMPLETED: 'Completed',
  FAILED: 'Failed (dead-letter)',
  RETRYING: 'Failing (retries left)',
}

interface VariableRow {
  name: string
  operation: string
  value: string
}

/**
 * M2b collapsible filter rail (SPEC §4 Stage 1, §8): AND between categories, OR within
 * one. Collapses to the compiled-criteria chips once a search runs; the echo and the
 * copy-as-cURL block are SERVER-computed presentation — never recomputed here.
 */
export function SearchRail({
  engines,
  initial,
  currentSearch,
  response,
  busy,
  collapsed,
  onToggle,
  onSubmit,
}: Props) {
  if (collapsed) {
    return (
      <aside className="pane pane-search pane-collapsed">
        <button type="button" className="rail-toggle" onClick={onToggle} aria-expanded="false">
          Filters ▸
        </button>
        <div className="criteria-chips">
          {(response?.criteriaEcho ?? []).map((line) => (
            <span key={line} className="criteria-chip">
              {line}
            </span>
          ))}
        </div>
        <SaveViewControl search={currentSearch} />
      </aside>
    )
  }
  return (
    <aside className="pane pane-search">
      <button type="button" className="rail-toggle" onClick={onToggle} aria-expanded="true">
        Filters ▾
      </button>
      <RailForm
        engines={engines}
        initial={initial}
        response={response}
        busy={busy}
        onSubmit={onSubmit}
      />
      <SaveViewControl search={currentSearch} />
      {response !== undefined && <CriteriaPanel response={response} />}
    </aside>
  )
}

function RailForm({
  engines,
  initial,
  response,
  busy,
  onSubmit,
}: Omit<Props, 'collapsed' | 'onToggle' | 'currentSearch'>) {
  const [engineIds, setEngineIds] = useState<string[]>(initial?.engineIds ?? [])
  const [statuses, setStatuses] = useState<InstanceStatus[]>(initial?.statuses ?? [])
  const [definitionKey, setDefinitionKey] = useState(initial?.processDefinitionKey ?? '')
  const [businessKey, setBusinessKey] = useState(initial?.businessKey ?? '')
  const [businessKeyLike, setBusinessKeyLike] = useState(initial?.businessKeyLike ?? '')
  const [startedAfter, setStartedAfter] = useState(() => splitLocal(initial?.startedAfter))
  const [startedBefore, setStartedBefore] = useState(() => splitLocal(initial?.startedBefore))
  const [failedAfter, setFailedAfter] = useState(() => splitLocal(initial?.failureTimeAfter))
  const [failedBefore, setFailedBefore] = useState(() => splitLocal(initial?.failureTimeBefore))
  const [errorText, setErrorText] = useState(initial?.errorText ?? '')
  const [currentActivity, setCurrentActivity] = useState(initial?.currentActivity ?? '')
  const [sortBy, setSortBy] = useState(initial?.sortBy ?? 'startTime')
  const [variables, setVariables] = useState<VariableRow[]>(
    (initial?.variables ?? []).map((v) => ({
      name: v.name ?? '',
      operation: v.operation ?? 'equals',
      value: typeof v.value === 'string' ? v.value : JSON.stringify(v.value),
    })),
  )

  const toggle = <T,>(list: T[], item: T): T[] =>
    list.includes(item) ? list.filter((x) => x !== item) : [...list, item]

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const usedVariables = variables.filter((v) => v.name !== '')
    onSubmit({
      engineIds: engineIds.length > 0 ? engineIds : undefined,
      statuses: statuses.length > 0 ? statuses : undefined,
      processDefinitionKey: definitionKey || undefined,
      businessKey: businessKey || undefined,
      businessKeyLike: businessKeyLike || undefined,
      startedAfter: localToIso(startedAfter),
      startedBefore: localToIso(startedBefore),
      failureTimeAfter: localToIso(failedAfter),
      failureTimeBefore: localToIso(failedBefore),
      errorText: errorText || undefined,
      currentActivity: currentActivity || undefined,
      sortBy: sortBy === 'startTime' ? undefined : sortBy,
      variables:
        usedVariables.length > 0
          ? usedVariables.map((v) => ({ name: v.name, operation: v.operation, value: v.value }))
          : undefined,
    })
  }

  // §8 facets: counts only for statuses the executed plan could observe; any count under
  // truncation/engine failure is a lower bound and rendered as "≥ n" (iron rule).
  const facet = (status: InstanceStatus): string => {
    const count = response?.statusCounts?.[status]
    if (count === undefined) return ''
    const lower = Object.values(response?.perEngine ?? {}).some(
      (r) => r.ok !== true || r.dlqScan !== undefined || r.failingScan !== undefined,
    )
    return ` (${lower ? '≥ ' : ''}${formatCount(count)})`
  }

  return (
    <form className="search-panel" onSubmit={submit}>
      <h2>Search</h2>

      <fieldset>
        <legend>Engines (all when none checked)</legend>
        {engines.map((engine) => {
          // W1#4 (theme T6, R-SEM-17): the engines list now includes non-active engines —
          // greyed-with-reason here (not searchable), never silently omitted. Still
          // un-tickable if a stale URL selection carries one.
          const inactive = isInactiveLifecycle(engine.lifecycle)
          const checked = engine.id !== undefined && engineIds.includes(engine.id)
          return (
            <label
              key={engine.id}
              className={`check-row${inactive ? ' check-row-gated' : ''}`}
              title={
                inactive && engine.lifecycle !== undefined
                  ? `Not searchable: ${lifecycleGloss(engine.lifecycle)}`
                  : undefined
              }
            >
              <input
                type="checkbox"
                checked={checked}
                disabled={inactive && !checked}
                onChange={() => {
                  if (engine.id !== undefined) setEngineIds(toggle(engineIds, engine.id))
                }}
              />
              {engine.name ?? engine.id}
              {inactive ? (
                <span className="health-down"> ({engine.lifecycle} — not searchable)</span>
              ) : (
                engine.reachable !== true && <span className="health-down"> (unreachable)</span>
              )}
            </label>
          )
        })}
      </fieldset>

      <fieldset>
        <legend>Status</legend>
        {ALL_STATUSES.map((status) => (
          <label key={status} className="check-row">
            <input
              type="checkbox"
              checked={statuses.includes(status)}
              onChange={() => {
                setStatuses(toggle(statuses, status))
              }}
            />
            {STATUS_LABELS[status]}
            {facet(status)}
          </label>
        ))}
      </fieldset>

      <fieldset>
        <legend>Process</legend>
        <label>
          Definition key
          <input
            value={definitionKey}
            onChange={(e) => {
              setDefinitionKey(e.target.value)
            }}
            placeholder="orderFulfilment"
          />
        </label>
        <label>
          Business key (exact)
          <input
            value={businessKey}
            onChange={(e) => {
              setBusinessKey(e.target.value)
            }}
            placeholder="ORD-4711"
          />
        </label>
        <label>
          Business key contains
          <input
            value={businessKeyLike}
            onChange={(e) => {
              setBusinessKeyLike(e.target.value)
            }}
            placeholder="ORD-"
          />
        </label>
        <label>
          Current activity
          <input
            value={currentActivity}
            onChange={(e) => {
              setCurrentActivity(e.target.value)
            }}
            placeholder="chargeCard"
          />
        </label>
        <label>
          Error text
          <input
            value={errorText}
            onChange={(e) => {
              setErrorText(e.target.value)
            }}
            placeholder="SocketTimeout"
          />
        </label>
      </fieldset>

      <fieldset>
        <legend>Started</legend>
        <DateTimeFilterField label="After" value={startedAfter} onChange={setStartedAfter} />
        <DateTimeFilterField label="Before" value={startedBefore} onChange={setStartedBefore} />
      </fieldset>

      <fieldset>
        <legend>Failure time</legend>
        <DateTimeFilterField label="After" value={failedAfter} onChange={setFailedAfter} />
        <DateTimeFilterField label="Before" value={failedBefore} onChange={setFailedBefore} />
      </fieldset>

      <fieldset>
        <legend>Variables</legend>
        {variables.length > 0 && (
          <p className="rail-hint">Variable search is unindexed — narrow by definition.</p>
        )}
        {variables.map((variable, i) => (
          <div key={i} className="var-row">
            <input
              placeholder="name"
              aria-label={`variable ${String(i + 1)} name`}
              value={variable.name}
              onChange={(e) => {
                setVariables(
                  variables.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)),
                )
              }}
            />
            <select
              aria-label={`variable ${String(i + 1)} operation`}
              value={variable.operation}
              onChange={(e) => {
                setVariables(
                  variables.map((x, j) => (j === i ? { ...x, operation: e.target.value } : x)),
                )
              }}
            >
              <option value="equals">=</option>
              <option value="like">like</option>
              <option value="greaterThan">&gt;</option>
              <option value="lessThan">&lt;</option>
            </select>
            <input
              placeholder="value"
              aria-label={`variable ${String(i + 1)} value`}
              value={variable.value}
              onChange={(e) => {
                setVariables(
                  variables.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)),
                )
              }}
            />
            <button
              type="button"
              aria-label={`remove variable ${String(i + 1)}`}
              onClick={() => {
                setVariables(variables.filter((_, j) => j !== i))
              }}
            >
              ✕
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={() => {
            setVariables([...variables, { name: '', operation: 'equals', value: '' }])
          }}
        >
          + variable filter
        </button>
      </fieldset>

      <fieldset>
        <legend>Sort</legend>
        <label>
          Order by
          <select
            value={sortBy}
            onChange={(e) => {
              setSortBy(e.target.value)
            }}
          >
            <option value="startTime">Start time (newest first)</option>
            <option value="failureTime">Failure time (newest first)</option>
          </select>
        </label>
      </fieldset>

      <button type="submit" className="primary" disabled={busy}>
        {busy ? 'Searching…' : 'Search'}
      </button>
    </form>
  )
}

/** Server-computed presentation (SearchResponse.criteriaEcho / .curl) — rendered verbatim. */
function CriteriaPanel({ response }: { response: SearchResponse }) {
  const curl = response.curl
  return (
    <div className="criteria-panel">
      <h3>Compiled criteria</h3>
      <ul className="criteria-echo">
        {(response.criteriaEcho ?? []).map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
      {curl !== undefined && (
        <>
          <h3>
            As cURL <CopyButton text={curl} />
          </h3>
          <pre className="curl-block">
            <code>{curl}</code>
          </pre>
        </>
      )}
    </div>
  )
}

/**
 * Sev2 usability cluster (#118 item 3): a single `type="datetime-local"` control's internal
 * date+time+AM/PM sub-segments trap keyboard Tab navigation when the field starts empty (a
 * native browser quirk, not app code — confirmed via no custom focus/keydown handling
 * anywhere near these fields). Two plain `type="date"` + `type="time"` inputs have far fewer
 * sub-segments each, so Tab reliably exits to the next control.
 */
export interface LocalDateTime {
  date: string
  time: string
}

function DateTimeFilterField({
  label,
  value,
  onChange,
}: {
  label: string
  value: LocalDateTime
  onChange: (value: LocalDateTime) => void
}) {
  return (
    <span className="datetime-filter-field">
      <label>
        {label} date
        <input
          type="date"
          value={value.date}
          onChange={(e) => {
            onChange({ ...value, date: e.target.value })
          }}
        />
      </label>
      <label>
        {label} time
        <input
          type="time"
          value={value.time}
          onChange={(e) => {
            onChange({ ...value, time: e.target.value })
          }}
        />
      </label>
    </span>
  )
}

export function splitLocal(iso: string | undefined): LocalDateTime {
  if (iso === undefined) return { date: '', time: '' }
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return { date: '', time: '' }
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    date: `${String(parsed.getFullYear())}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}`,
    time: `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`,
  }
}

export function localToIso({ date, time }: LocalDateTime): string | undefined {
  if (date === '') return undefined
  const parsed = new Date(`${date}T${time === '' ? '00:00' : time}`)
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString()
}
