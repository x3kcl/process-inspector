import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type { components } from '../api/schema'
import { ActionError } from '../api/actions'
import { parseActionProblem, problemBanner } from '../actions/problem'
import { useToast } from '../components/toast'

type Preview = components['schemas']['HistoryPreview']
type Body = components['schemas']['HistoryDeleteRequest']

/** ADMIN wizard: delete finished history older than a bound. */
export function HousekeepingPage() {
  const toast = useToast()
  const [scope, setScope] = useState<'bpmn' | 'cmmn'>('bpmn')
  const [definitionKey, setDefinitionKey] = useState('')
  const [version, setVersion] = useState('')
  const [businessKey, setBusinessKey] = useState('')
  const [finishedBefore, setFinishedBefore] = useState('')
  const [reason, setReason] = useState('')
  const [count, setCount] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)

  const body = (): Body => ({
    scope,
    definitionKey: definitionKey.trim() || undefined,
    definitionVersion: version.trim() === '' ? undefined : Number(version),
    businessKey: businessKey.trim() || undefined,
    finishedBefore: finishedBefore === '' ? undefined : new Date(finishedBefore).toISOString(),
    reason: reason.trim() || undefined,
    confirmedCount: count.trim() === '' ? undefined : Number(count),
  })

  const previewM = useMutation({
    mutationFn: async () => {
      const { data, error, response } = await api.POST('/api/bulk/history/preview', {
        body: body(),
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
    onSuccess: (page) => {
      setPreview(page)
      setCount(String(page.count ?? 0))
    },
  })
  const submitM = useMutation({
    mutationFn: async () => {
      const { data, error, response } = await api.POST('/api/bulk/history', { body: body() })
      if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
      return data
    },
    onSuccess: (job) => {
      toast({ kind: 'success', text: `Housekeeping ${job.id ?? ''} queued.` })
    },
  })

  return (
    <section>
      <h1>Delete completed history</h1>
      <p>
        Finished instances only. An ended-before bound is required. On a production engine, name the
        definition and type the count. This cannot be undone.
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault()
        }}
      >
        <label>
          Scope{' '}
          <select
            value={scope}
            onChange={(event) => {
              setScope(event.target.value === 'cmmn' ? 'cmmn' : 'bpmn')
            }}
          >
            <option value="bpmn">process</option>
            <option value="cmmn">case</option>
          </select>
        </label>
        <label>
          Definition key{' '}
          <input
            value={definitionKey}
            onChange={(event) => {
              setDefinitionKey(event.target.value)
            }}
          />
        </label>
        <label>
          Version{' '}
          <input
            value={version}
            onChange={(event) => {
              setVersion(event.target.value)
            }}
          />
        </label>
        <label>
          Business key{' '}
          <input
            value={businessKey}
            onChange={(event) => {
              setBusinessKey(event.target.value)
            }}
          />
        </label>
        <label>
          Ended before{' '}
          <input
            type="datetime-local"
            value={finishedBefore}
            onChange={(event) => {
              setFinishedBefore(event.target.value)
            }}
          />
        </label>
        <label>
          Reason{' '}
          <textarea
            value={reason}
            onChange={(event) => {
              setReason(event.target.value)
            }}
          />
        </label>
        <button
          type="button"
          onClick={() => {
            previewM.mutate()
          }}
        >
          Preview
        </button>
        {preview !== null && (
          <p>
            {preview.capped ? 'At least ' : ''}
            {preview.count ?? 0} match{preview.prodInScope ? ' (includes production)' : ''}.
          </p>
        )}
        <label>
          Type the count{' '}
          <input
            value={count}
            onChange={(event) => {
              setCount(event.target.value)
            }}
          />
        </label>
        <button
          type="button"
          disabled={reason.trim().length < 10 || submitM.isPending}
          onClick={() => {
            submitM.mutate()
          }}
        >
          Delete history
        </button>
      </form>
      {previewM.isError && <p role="alert">{previewM.error.message}</p>}
      {submitM.isError && (
        <p role="alert">
          {submitM.error instanceof ActionError
            ? problemBanner(submitM.error.problem)
            : submitM.error.message}
        </p>
      )}
    </section>
  )
}
