import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import type { components } from '../../api/schema'
import { useInstanceAction } from '../../api/actions'
import { useEngines } from '../../api/useEngines'
import { roleOn, useMe } from '../../api/me'
import { actionGate, VERBS } from '../../actions/catalog'
import { GuardFields } from '../../components/GuardFields'
import { ModalShell } from '../../components/ModalShell'
import { useProdGuard } from '../../actions/guard'
import { reasonRule } from '../../actions/catalog'
import { ActionHint } from '../../components/ActionHint'
import { useToast } from '../../components/toast'
import { problemBanner } from '../../actions/problem'

type Page = components['schemas']['ParticipantPage']

interface Props {
  engineId: string
  instanceId: string
}

export default function ParticipantsTab({ engineId, instanceId }: Props) {
  const engines = useEngines()
  const engine = (engines.data ?? []).find((candidate) => candidate.id === engineId)
  const me = useMe()
  const roleHint = roleOn(me.data, engineId)
  const gate = actionGate({
    meta: VERBS.addParticipant,
    roleHint,
    engineMode: engine?.mode,
    environment: engine?.environment,
  })
  const action = useInstanceAction(engineId, instanceId)
  const toast = useToast()
  const [open, setOpen] = useState(false)
  const [user, setUser] = useState('')
  const rule = reasonRule(VERBS.addParticipant.tier, engine?.environment)
  const guard = useProdGuard({ reasonRule: rule, environment: engine?.environment })
  const query = useQuery({
    queryKey: ['instance', engineId, instanceId, 'identitylinks'],
    queryFn: async (): Promise<Page> => {
      const { data, error, response } = await api.GET(
        '/api/instances/{engineId}/{instanceId}/identitylinks',
        { params: { path: { engineId, instanceId } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })

  if (query.isPending) return <div className="zero-state">Loading participants…</div>
  if (query.isError) {
    return (
      <div className="error-banner" role="alert">
        Participants unavailable: {query.error.message}
      </div>
    )
  }
  const rows = query.data.rows ?? []
  return (
    <div>
      <p>Who can see this case. The starter link is history and stays.</p>
      {!query.data.running && <p>This case has ended. Links are read from history.</p>}
      <table>
        <thead>
          <tr>
            <th>User</th>
            <th>Type</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.user ?? ''}-${row.type ?? ''}`}>
              <td>{row.user}</td>
              <td>{row.type}</td>
              <td>
                {row.removable && query.data.running ? (
                  <button
                    type="button"
                    disabled={!gate.enabled || action.isPending}
                    onClick={() => {
                      action.mutate(
                        {
                          verb: VERBS.removeParticipant.verb,
                          body: { link: { family: 'users', identityId: row.user, type: row.type } },
                        },
                        {
                          onSuccess: (result) => {
                            toast({ kind: 'success', text: result.deltaStatement ?? 'Removed.' })
                            void query.refetch()
                          },
                        },
                      )
                    }}
                  >
                    Remove
                  </button>
                ) : (
                  <span>kept</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <span className="action-slot">
        <button
          type="button"
          disabled={!gate.enabled || !query.data.running}
          onClick={() => {
            setOpen(true)
          }}
        >
          Add participant
        </button>
        {!gate.enabled && gate.reason !== undefined && (
          <ActionHint text={gate.reason} tone="gate" />
        )}
      </span>
      {open && (
        <ModalShell
          title="Add participant"
          environment={engine?.environment}
          onClose={() => {
            setOpen(false)
          }}
          footer={
            <button
              type="button"
              disabled={user.trim() === '' || !guard.reasonOk || action.isPending}
              onClick={() => {
                action.mutate(
                  {
                    verb: VERBS.addParticipant.verb,
                    body: {
                      link: { family: 'users', identityId: user.trim(), type: 'participant' },
                      ...(guard.reason.trim() === '' ? {} : { reason: guard.reason.trim() }),
                    },
                  },
                  {
                    onSuccess: (result) => {
                      setOpen(false)
                      toast({ kind: 'success', text: result.deltaStatement ?? 'Added.' })
                      void query.refetch()
                    },
                  },
                )
              }}
            >
              Add
            </button>
          }
        >
          <label>
            User id{' '}
            <input
              value={user}
              onChange={(event) => {
                setUser(event.target.value)
              }}
            />
          </label>
          <GuardFields guard={guard} />
          {action.error !== null && <p role="alert">{problemBanner(action.error.problem)}</p>}
        </ModalShell>
      )}
    </div>
  )
}
