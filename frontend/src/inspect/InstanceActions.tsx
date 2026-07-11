// Instance-level verbs on the vitals header (SPEC §5): suspend/activate (tier 0,
// REVERSIBLE, single-click), terminate/delete (tier 3, the destructive modal), and the
// v1.1 flow-surgery pair — change-state (simulation-first modal) and restart-as-new
// (ended instances only). The row follows greyed-never-hidden: every gate names itself
// in the tooltip.
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
import { roleOn, useMe } from '../api/me'
import { ActionHint } from '../components/ActionHint'
import { useToast } from '../components/toast'
import { ChangeStateModal } from '../surgery/ChangeStateModal'
import { MigrateModal } from '../surgery/MigrateModal'
import { RestartModal } from '../surgery/RestartModal'

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
  const [changeStateOpen, setChangeStateOpen] = useState(false)
  const [migrateOpen, setMigrateOpen] = useState(false)
  const [restartOpen, setRestartOpen] = useState(false)
  const me = useMe()
  const roleHint = roleOn(me.data, engineId)
  const ended = vitals.flags?.ended === true || vitals.endTime !== undefined
  const suspended = vitals.status === 'SUSPENDED' || vitals.flags?.suspended === true
  // R-SAFE-05 (usability W3 sliver): the vitals carry the protected state, so every verb below
  // the ADMIN floor greys with the reason AND the badge shows — point-of-action visibility for
  // enforcement the BFF already does. true = protected; false/undefined = not (or unknown).
  const isProtected = vitals.protectedInstance === true
  const environment = engine?.environment
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`

  const pauseMeta = suspended ? VERBS.activate : VERBS.suspend
  const pauseGate = actionGate({
    meta: pauseMeta,
    roleHint,
    engineMode: engine?.mode,
    instanceEnded: ended,
    instanceProtected: isProtected,
  })
  const terminateGate = actionGate({
    meta: VERBS.terminate,
    roleHint,
    engineMode: engine?.mode,
    instanceEnded: ended,
    instanceProtected: isProtected,
  })
  const changeStateGate = actionGate({
    meta: VERBS.changeState,
    roleHint,
    engineMode: engine?.mode,
    instanceEnded: ended,
    instanceSuspended: suspended,
    instanceProtected: isProtected,
    capability: engine?.capabilities?.changeState,
    environment,
  })
  const migrateGate = actionGate({
    meta: VERBS.migrate,
    roleHint,
    engineMode: engine?.mode,
    instanceEnded: ended,
    instanceSuspended: suspended,
    instanceProtected: isProtected,
    capability: engine?.capabilities?.migration,
    environment,
  })
  const restartGate = actionGate({
    meta: VERBS.restartAsNew,
    roleHint,
    engineMode: engine?.mode,
    instanceEnded: ended,
    instanceProtected: isProtected,
    environment,
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
      {/* R-SAFE-05 point-of-action badge (usability W3): the operator sees the instance is
          protected right where the verbs live — the reason rides the title, and every verb
          below the ADMIN floor is greyed with the matching "Protected — L3 action required"
          gate. Enforcement is server-side (instance-protected guard); this is visibility. */}
      {isProtected && (
        <span
          className="protected-badge"
          role="status"
          title={
            vitals.protectionReason !== undefined && vitals.protectionReason !== ''
              ? `Protected (R-SAFE-05): ${vitals.protectionReason} — L3 (ADMIN) action required`
              : 'Protected (R-SAFE-05) — L3 (ADMIN) action required'
          }
        >
          🔒 Protected
        </span>
      )}
      {/* W2 #2 (T11): single-click stays (§5.0 queue-state doctrine — no confirm), but the
          REVERSIBLE badge is visible and the outcome toast names the compensating verb. */}
      <InlineConfirm
        meta={pauseMeta}
        gate={pauseGate}
        confirmText={`${pauseMeta.label} this case?`}
        twoStep={needsTwoStepConfirm(pauseMeta, environment)}
        pending={action.isPending && !terminateOpen}
        showReversibility
        onConfirm={() => {
          run(pauseMeta.verb, {})
        }}
      />
      <span className="action-slot">
        <button
          type="button"
          className="copy-btn action-btn"
          disabled={!changeStateGate.enabled}
          aria-describedby={changeStateGate.enabled ? undefined : 'change-state-hint'}
          title={
            changeStateGate.enabled
              ? `${VERBS.changeState.plain} — simulated before anything executes`
              : changeStateGate.detail
          }
          onClick={() => {
            setChangeStateOpen(true)
          }}
        >
          {VERBS.changeState.label}
        </button>
        {!changeStateGate.enabled && changeStateGate.reason !== undefined && (
          <ActionHint id="change-state-hint" text={changeStateGate.reason} tone="gate" />
        )}
      </span>
      <span className="action-slot">
        <button
          type="button"
          className="copy-btn action-btn"
          disabled={!migrateGate.enabled}
          aria-describedby={migrateGate.enabled ? undefined : 'migrate-hint'}
          title={migrateGate.enabled ? `${VERBS.migrate.plain} · IRREVERSIBLE` : migrateGate.detail}
          onClick={() => {
            setMigrateOpen(true)
          }}
        >
          {VERBS.migrate.label}
        </button>
        {!migrateGate.enabled && migrateGate.reason !== undefined && (
          <ActionHint id="migrate-hint" text={migrateGate.reason} tone="gate" />
        )}
      </span>
      <span className="action-slot">
        <button
          type="button"
          className="copy-btn action-btn"
          disabled={!restartGate.enabled}
          aria-describedby={restartGate.enabled ? undefined : 'restart-hint'}
          title={restartGate.enabled ? VERBS.restartAsNew.plain : restartGate.detail}
          onClick={() => {
            setRestartOpen(true)
          }}
        >
          {VERBS.restartAsNew.label}
        </button>
        {!restartGate.enabled && restartGate.reason !== undefined && (
          <ActionHint id="restart-hint" text={restartGate.reason} tone="gate" />
        )}
      </span>
      <span className="action-slot">
        <button
          type="button"
          className="copy-btn action-btn action-danger"
          disabled={!terminateGate.enabled}
          aria-describedby={terminateGate.enabled ? undefined : 'terminate-hint'}
          title={
            terminateGate.enabled ? `${VERBS.terminate.plain} · IRREVERSIBLE` : terminateGate.detail
          }
          onClick={() => {
            action.reset()
            setTerminateOpen(true)
          }}
        >
          {VERBS.terminate.label}
        </button>
        {!terminateGate.enabled && terminateGate.reason !== undefined && (
          <ActionHint id="terminate-hint" text={terminateGate.reason} tone="gate" />
        )}
      </span>
      {changeStateOpen && (
        <ChangeStateModal
          engineId={engineId}
          instanceId={instanceId}
          vitals={vitals}
          engine={engine}
          onClose={() => {
            setChangeStateOpen(false)
          }}
        />
      )}
      {migrateOpen && (
        <MigrateModal
          engineId={engineId}
          instanceId={instanceId}
          vitals={vitals}
          engine={engine}
          onClose={() => {
            setMigrateOpen(false)
          }}
        />
      )}
      {restartOpen && (
        <RestartModal
          engineId={engineId}
          instanceId={instanceId}
          vitals={vitals}
          engine={engine}
          onClose={() => {
            setRestartOpen(false)
          }}
        />
      )}
      {terminateOpen && (
        <TerminateModal
          engineId={engineId}
          instanceId={instanceId}
          vitals={vitals}
          engine={engine}
          pending={action.isPending}
          problem={action.error?.problem}
          onConfirm={(reason, ticketId) => {
            run(VERBS.terminate.verb, { reason, ticketId })
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
  onConfirm: (reason: string, ticketId?: string) => void
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
