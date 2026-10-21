import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, ApiError } from '../api/client'
import type { components } from '../api/schema'

type JobsPageDto = components['schemas']['JobsPage']

/** Cross-engine job list. Capped engines are a lower bound (#515). */
export function JobsPage() {
  const [lane, setLane] = useState('executable')
  const [older, setOlder] = useState('5')
  const query = useQuery({
    queryKey: ['jobs', lane, older],
    queryFn: async (): Promise<JobsPageDto> => {
      const { data, error, response } = await api.GET('/api/jobs', {
        params: {
          query: {
            lane,
            olderThanMinutes: Number(older),
          },
        },
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const page = query.data
  return (
    <section>
      <h1>Jobs</h1>
      <form
        onSubmit={(event) => {
          event.preventDefault()
        }}
      >
        <label>
          Lane{' '}
          <select
            value={lane}
            onChange={(event) => {
              setLane(event.target.value)
            }}
          >
            <option value="executable">executable</option>
            <option value="timer">timer</option>
            <option value="suspended">suspended</option>
            <option value="deadletter">deadletter</option>
            <option value="history">history</option>
          </select>
        </label>
        <label>
          Older than minutes{' '}
          <input
            value={older}
            onChange={(event) => {
              setOlder(event.target.value)
            }}
          />
        </label>
      </form>
      {(page?.cappedEngines ?? []).length > 0 && (
        <p>Lower bound. Capped or unread engines: {(page?.cappedEngines ?? []).join(', ')}</p>
      )}
      <table>
        <thead>
          <tr>
            <th>Engine</th>
            <th>Job</th>
            <th>Instance</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          {(page?.rows ?? []).map((row) => (
            <tr key={`${row.engineId ?? ''}-${row.jobId ?? ''}`}>
              <td>{row.engineId}</td>
              <td>
                <code>{row.jobId}</code>
              </td>
              <td>
                {row.processInstanceId != null && row.engineId != null ? (
                  <a href={`/inspect/${row.engineId}/${encodeURIComponent(row.processInstanceId)}`}>
                    {row.processInstanceId}
                  </a>
                ) : (
                  '—'
                )}
              </td>
              <td>{row.createTime ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
