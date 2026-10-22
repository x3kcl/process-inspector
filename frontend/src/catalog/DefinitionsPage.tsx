// Cross-engine definition catalog (#483). VIEWER read. Counts are floors when the
// response says so. Density follows the global toggle via .catalog-table.
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, ApiError } from '../api/client'
import type { components } from '../api/schema'

type Catalog = components['schemas']['DefinitionCatalogResponse']
type Row = components['schemas']['CatalogDefinition']

export function DefinitionsPage() {
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [latestOnly, setLatestOnly] = useState(true)
  const query = useQuery({
    queryKey: ['definitions', key, name, latestOnly],
    queryFn: async (): Promise<Catalog> => {
      const { data, error, response } = await api.GET('/api/definitions', {
        params: {
          query: {
            ...(key.trim() !== '' ? { key: key.trim() } : {}),
            ...(name.trim() !== '' ? { name: name.trim() } : {}),
            latestOnly,
          },
        },
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const page = query.data
  const failures = Object.entries(page?.perEngine ?? {}).filter(([, leg]) => leg.ok === false)
  return (
    <section>
      <h1>Definitions</h1>
      <p className="muted">
        Deployed process and case definitions across the engines you can read. Running counts are
        count-only.
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault()
        }}
      >
        <label>
          Key{' '}
          <input
            aria-label="Definition key"
            value={key}
            onChange={(event) => {
              setKey(event.target.value)
            }}
          />
        </label>
        <label>
          Name{' '}
          <input
            aria-label="Definition name"
            value={name}
            onChange={(event) => {
              setName(event.target.value)
            }}
          />
        </label>
        <label>
          <input
            type="checkbox"
            checked={latestOnly}
            onChange={(event) => {
              setLatestOnly(event.target.checked)
            }}
          />{' '}
          Latest version only
        </label>
      </form>
      {query.isPending && <p>Loading definitions…</p>}
      {query.isError && <p role="alert">{query.error.message}</p>}
      {page?.countsBounded === true && (
        <p>Counts are a lower bound — a list or a count leg was capped or failed.</p>
      )}
      {failures.map(([id, leg]) => (
        <p key={id} role="status">
          {id}: {leg.error ?? 'unreachable'}
        </p>
      ))}
      <table className="catalog-table versions-table">
        <thead>
          <tr>
            <th>Key</th>
            <th>Version</th>
            <th>Engine</th>
            <th>Kind</th>
            <th>Name</th>
            <th>Running</th>
            <th>State</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(page?.definitions ?? []).map((row) => (
            <DefinitionRow key={`${row.engineId ?? ''}-${row.definitionId ?? ''}`} row={row} />
          ))}
        </tbody>
      </table>
    </section>
  )
}

function DefinitionRow({ row }: { row: Row }) {
  const engineId = row.engineId ?? ''
  const definitionId = row.definitionId ?? ''
  const count =
    row.runningCount == null
      ? 'unknown'
      : `${row.countLowerBound === true ? '≥ ' : ''}${String(row.runningCount)}`
  return (
    <tr>
      <td>
        <code>{row.key}</code>
      </td>
      <td>
        v{String(row.version ?? '?')}
        {row.latest === true && <span className="badge badge-latest"> latest</span>}
      </td>
      <td>{engineId}</td>
      <td>{row.kind}</td>
      <td>{row.name ?? '—'}</td>
      <td className="num">{count}</td>
      <td>
        {row.suspended === true ? 'suspended' : 'active'}
        {row.protectedDefinition === true && ' · protected'}
      </td>
      <td>
        <Link to={`/definitions/${engineId}/${encodeURIComponent(definitionId)}`}>Open</Link>
        {' · '}
        <Link
          to={`/search?engineId=${encodeURIComponent(engineId)}&processDefinitionId=${encodeURIComponent(definitionId)}`}
        >
          Instances
        </Link>
        {row.kind === 'BPMN' && row.key != null && (
          <>
            {' · '}
            <Link to={`/definitions/${engineId}/${encodeURIComponent(row.key)}/versions`}>
              Migrate
            </Link>
          </>
        )}
      </td>
    </tr>
  )
}
