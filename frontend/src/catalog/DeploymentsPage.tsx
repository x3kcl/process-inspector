// Deployment browser (#483 S4). Delete lives on the detail page. Upload stays out (#505).
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, ApiError } from '../api/client'
import type { components } from '../api/schema'

type Page = components['schemas']['DeploymentCatalogResponse']

export function DeploymentsPage() {
  const [name, setName] = useState('')
  const query = useQuery({
    queryKey: ['deployments', name],
    queryFn: async (): Promise<Page> => {
      const { data, error, response } = await api.GET('/api/deployments', {
        params: { query: name.trim() === '' ? {} : { name: name.trim() } },
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const failures = Object.entries(query.data?.perEngine ?? {}).filter(([, leg]) => leg.ok === false)
  return (
    <section>
      <h1>Deployments</h1>
      <p className="muted">What is deployed where. Uploading a new deployment is not offered.</p>
      <form
        onSubmit={(event) => {
          event.preventDefault()
        }}
      >
        <label>
          Name{' '}
          <input
            aria-label="Deployment name"
            value={name}
            onChange={(event) => {
              setName(event.target.value)
            }}
          />
        </label>
      </form>
      {query.isPending && <p>Loading deployments…</p>}
      {query.isError && <p role="alert">{query.error.message}</p>}
      {failures.map(([id, leg]) => (
        <p key={id} role="status">
          {id}: {leg.error ?? 'unreachable'}
        </p>
      ))}
      <table className="catalog-table versions-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Engine</th>
            <th>Deployed</th>
            <th>Id</th>
          </tr>
        </thead>
        <tbody>
          {(query.data?.deployments ?? []).map((row) => (
            <tr key={`${row.engineId ?? ''}-${row.deploymentId ?? ''}`}>
              <td>{row.name ?? '—'}</td>
              <td>{row.engineId}</td>
              <td>{row.deploymentTime ?? '—'}</td>
              <td>
                <Link
                  to={`/deployments/${row.engineId ?? ''}/${encodeURIComponent(row.deploymentId ?? '')}`}
                >
                  {row.deploymentId}
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
