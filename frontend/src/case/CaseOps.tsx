import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import { useCaseAction } from '../api/caseActions'
import { VERBS } from '../actions/catalog'
import { useToast } from '../components/toast'
import type { CasePlanItems } from '../api/model'
import { flattenPlanItems } from './planItemModel'

interface Props {
  engineId: string
  caseInstanceId: string
  businessKey?: string
  planItems?: CasePlanItems
}

/** Case rescue verbs. The engine stores no delete reason on terminate or delete. */
export function CaseOps({ engineId, caseInstanceId, businessKey, planItems }: Props) {
  const action = useCaseAction(engineId, caseInstanceId)
  const toast = useToast()
  const [reason, setReason] = useState('')
  const [token, setToken] = useState('')
  const [name, setName] = useState('')
  const [nextValue, setNextValue] = useState('')
  const [expected, setExpected] = useState('')
  const variables = useQuery({
    queryKey: ['case', engineId, caseInstanceId, 'variables'],
    queryFn: async () => {
      const { data, error, response } = await api.GET(
        '/api/cases/{engineId}/{caseInstanceId}/variables',
        { params: { path: { engineId, caseInstanceId } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const run = (verb: string, body: Record<string, unknown>) => {
    action.mutate(
      { verb, body: { reason, confirmToken: token || undefined, ...body } },
      {
        onSuccess: (result) => {
          toast({ kind: 'success', text: result.deltaStatement ?? 'Done.' })
        },
      },
    )
  }
  const rows = flattenPlanItems(planItems?.planItems ?? [])
  return (
    <section>
      <h2>Case actions</h2>
      <p>Terminate and delete send no reason to the engine. The reason below is the audit row.</p>
      <label>
        Reason{' '}
        <input
          value={reason}
          onChange={(event) => {
            setReason(event.target.value)
          }}
        />
      </label>
      <label>
        Type the business key or case id{' '}
        <input
          value={token}
          onChange={(event) => {
            setToken(event.target.value)
          }}
        />
      </label>
      <button
        type="button"
        disabled={reason.trim().length < 10 || action.isPending}
        onClick={() => {
          run(VERBS.terminateCase.verb, {})
        }}
      >
        {VERBS.terminateCase.label}
      </button>
      <button
        type="button"
        disabled={reason.trim().length < 10 || action.isPending}
        onClick={() => {
          run(VERBS.deleteCase.verb, {})
        }}
      >
        {VERBS.deleteCase.label}
      </button>
      <p>Expected token: {businessKey || caseInstanceId}</p>
      <h3>Plan items</h3>
      <ul>
        {rows.map(({ item }) => (
          <li key={item.id ?? item.elementId}>
            {item.name ?? item.elementId} ({item.state}){' '}
            <button
              type="button"
              onClick={() => {
                run(VERBS.planItemAction.verb, {
                  plan: { planItemInstanceId: item.id, action: 'trigger' },
                })
              }}
            >
              Trigger
            </button>
            <button
              type="button"
              onClick={() => {
                run(VERBS.changePlanItem.verb, {
                  plan: { definitionId: item.elementId, action: 'activate' },
                })
              }}
            >
              Activate
            </button>
            <button
              type="button"
              onClick={() => {
                run(VERBS.changePlanItem.verb, {
                  plan: { definitionId: item.elementId, action: 'terminate' },
                })
              }}
            >
              Terminate item
            </button>
          </li>
        ))}
      </ul>
      <h3>Variables</h3>
      <ul>
        {(Array.isArray(variables.data) ? variables.data : []).map((row) => (
          <li key={String(row.name)}>
            {String(row.name)} = {JSON.stringify(row.value ?? null)}
          </li>
        ))}
      </ul>
      <label>
        Name{' '}
        <input
          value={name}
          onChange={(event) => {
            setName(event.target.value)
          }}
        />
      </label>
      <label>
        Expected old value{' '}
        <input
          value={expected}
          onChange={(event) => {
            setExpected(event.target.value)
          }}
        />
      </label>
      <label>
        New value{' '}
        <input
          value={nextValue}
          onChange={(event) => {
            setNextValue(event.target.value)
          }}
        />
      </label>
      <button
        type="button"
        disabled={name.trim() === '' || action.isPending}
        onClick={() => {
          run(VERBS.editCaseVariable.verb, {
            variable: { name: name.trim(), value: nextValue, expectedOldValue: expected },
          })
        }}
      >
        {VERBS.editCaseVariable.label}
      </button>
      {action.isError && <p role="alert">{action.error.problem.detail}</p>}
    </section>
  )
}
