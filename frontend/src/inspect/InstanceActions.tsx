// Instance-level verbs on the vitals header (SPEC §5): suspend/activate (tier 0,
// REVERSIBLE, single-click) and terminate/delete (tier 3, the destructive modal). The
// row follows greyed-never-hidden: every gate names itself in the tooltip.
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { EngineDto, InstanceDetail } from '../api/model'
import { useInstanceAction } from '../api/actions'
import { fetchInstanceHierarchy } from '../api/queries'
import { DestructiveModal } from '../actions/DestructiveModal'
import { InlineConfirm } from '../actions/InlineConfirm'
import { VERBS, actionGate, needsTwoStepConfirm } from '../actions/catalog'
import { cascadeVictims } from '../actions/cascade'
import { problemBanner } from '../actions/problem'
import type { ActionProblem } from '../actions/problem'
import { currentRoleHint } from '../lib/roleHint'
import { useToast } from '../components/toast'

interface Props {
  engineId: string
  instanceId: string
  vitals: InstanceDetail
  engine?: EngineDto
}

export function InstanceActions({ engineId, instanceId, vitals, engine }: Props) {
  const toast = useToast()
  const action = useInstanceAction(engineId, instanceId)
  const [terminateOpen, setTerminateOpen] = useState(false)
  const roleHint = currentRoleHint()
  const ended = vitals.flags?.ended === true || vitals.endTime !== undefined
  const suspended = vitals.status === 'SUSPENDED' || vitals.flags?.suspended === true
  const environment = engine?.environment
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`

  const pauseMeta = suspended ? VERBS.activate : VERBS.suspend
  const pauseGate = actionGate({
    meta: pauseMeta,
    roleHint,
    engineMode: engine?.mode,
    instanceEnded: ended,
  })
  const terminateGate = actionGate({
    meta: VERBS.terminate,
    roleHint,
    engineMode: engine?.mode,
    instanceEnded: ended,
  })

  const run = (verb: string, body: Parameters<typeof action.mutate>[0]['body']) => {
    action.mutate(
      { verb, body },
      {
        onSuccess: (result) => {
          setTerminateOpen(false)
          toast({
            kind: 'success',
            text: result.deltaStatement ?? `${verb} completed`,
            auditPath,
          })
        },
        onError: (error) => {
          // The modal renders its own problem banner; inline verbs toast the failure.
          if (!terminateOpen) toast({ kind: 'error', text: problemBanner(error.problem) })
        },
      },
    )
  }

  return (
    <div className="instance-actions">
      <InlineConfirm
        meta={pauseMeta}
        gate={pauseGate}
        confirmText={`${pauseMeta.label} this case?`}
        twoStep={needsTwoStepConfirm(pauseMeta, environment)}
        pending={action.isPending && !terminateOpen}
        onConfirm={() => {
          run(pauseMeta.verb, {})
        }}
      />
      <button
        type="button"
        className="copy-btn action-btn action-danger"
        disabled={!terminateGate.enabled}
        title={
          terminateGate.enabled
            ? `${VERBS.terminate.plain} · IRREVERSIBLE`
            : terminateGate.reason
        }
        onClick={() => {
          action.reset()
          setTerminateOpen(true)
        }}
      >
        {VERBS.terminate.label}
      </button>
      {terminateOpen && (
        <TerminateModal
          engineId={engineId}
          instanceId={instanceId}
          vitals={vitals}
          engine={engine}
          pending={action.isPending}
          problem={action.error?.problem}
          onConfirm={(reason) => {
            run(VERBS.terminate.verb, { reason })
          }}
          onClose={() => {
            setTerminateOpen(false)
          }}
        />
      )}
    </div>
  )
}

function TerminateModal({
  engineId,
  instanceId,
  vitals,
  engine,
  pending,
  problem,
  onConfirm,
  onClose,
}: {
  engineId: string
  instanceId: string
  vitals: InstanceDetail
  engine?: EngineDto
  pending: boolean
  problem?: ActionProblem
  onConfirm: (reason: string) => void
  onClose: () => void
}) {
  // Cascade victims come from the hierarchy tree — fetched only while the modal is open.
  const hierarchy = useQuery({
    queryKey: ['instance', engineId, instanceId, 'hierarchy'],
    queryFn: () => fetchInstanceHierarchy({ engineId, instanceId }),
    staleTime: 0,
  })
  const victims = hierarchy.isPending
    ? ('loading' as const)
    : hierarchy.isError
      ? ('unavailable' as const)
      : cascadeVictims(hierarchy.data, instanceId)

  // The typed token mirrors the BFF's server-fresh check: business key, else instance id.
  const hasBusinessKey = vitals.businessKey !== undefined && vitals.businessKey !== ''
  const expectedToken = hasBusinessKey ? (vitals.businessKey ?? instanceId) : instanceId
  const tokenName = hasBusinessKey ? 'business key' : 'instance id'
  const targetLabel = hasBusinessKey ? (vitals.businessKey ?? instanceId) : instanceId

  return (
    <DestructiveModal
      meta={VERBS.terminate}
      environment={engine?.environment}
      engineName={engine?.name ?? engineId}
      target={
        <ul className="modal-target-list">
          <li>
            Instance <code>{`${engineId}:${instanceId}`}</code>
          </li>
          <li>
            Definition{' '}
            {vitals.definitionName ?? vitals.definitionKey ?? vitals.processDefinitionId ?? '?'}
            {vitals.definitionVersion !== undefined && ` v${String(vitals.definitionVersion)}`}
          </li>
          <li>Status {vitals.status ?? '?'}</li>
          {hasBusinessKey && (
            <li>
              Business key <code>{vitals.businessKey}</code>
            </li>
          )}
        </ul>
      }
      cascade={{ victims }}
      expectedToken={expectedToken}
      tokenName={tokenName}
      confirmLabel={`Terminate ${targetLabel} permanently`}
      pending={pending}
      problem={problem}
      onConfirm={onConfirm}
      onClose={onClose}
    />
  )
}
