import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { fetchSystemInfo } from '../api/queries'

/** Version, properties, table counts, and executor scrape status (#509 #510). */
export function EngineSystemPage() {
  const { id = '' } = useParams()
  const query = useQuery({
    queryKey: ['engine-system', id],
    queryFn: () => fetchSystemInfo(id),
    enabled: id.length > 0,
  })
  const info = query.data
  const json = info === undefined ? '' : JSON.stringify(info, null, 2)

  return (
    <section className="engine-system">
      <h1>System info {id}</h1>
      {query.isPending && <p>Loading…</p>}
      {query.error instanceof Error && <p role="alert">{query.error.message}</p>}
      {info !== undefined && (
        <>
          <p>Version: {info.version ?? 'unknown'}</p>
          <p>
            Executor internals: {info.executorInternals ?? 'not exposed by this engine'} ({info.metricsStatus})
          </p>
          {(info.failures ?? []).length > 0 && <p>Unavailable: {(info.failures ?? []).join(', ')}</p>}
          <table>
            <thead>
              <tr>
                <th>Table</th>
                <th>Rows</th>
              </tr>
            </thead>
            <tbody>
              {(info.tables ?? []).map((row) => (
                <tr key={row.name}>
                  <td>{row.name}</td>
                  <td>{row.count ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p>
            <button
              type="button"
              onClick={() => {
                void navigator.clipboard.writeText(json)
              }}
            >
              Copy JSON
            </button>
            <button
              type="button"
              onClick={() => {
                const blob = new Blob([json], { type: 'application/json' })
                const url = URL.createObjectURL(blob)
                const link = document.createElement('a')
                link.href = url
                link.download = `${id}-system.json`
                link.click()
                URL.revokeObjectURL(url)
              }}
            >
              Download JSON
            </button>
          </p>
        </>
      )}
    </section>
  )
}
