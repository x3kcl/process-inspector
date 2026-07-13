// R-SAFE-05 write path (#165): mark/unmark an instance protected. BFF-store-only (no engine
// call, never destructive to engine state), so unlike the surgery modals this never gates
// behind a typed confirmation token — just the reason floor every protect/unprotect requires.
import type { EngineDto, InstanceDetail } from '../api/model'
import { useProtectInstance, useUnprotectInstance } from '../api/protection'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'

interface Props {
  engineId: string
  instanceId: string
  vitals: InstanceDetail
  engine?: EngineDto
  mode: 'protect' | 'unprotect'
  onClose: () => void
}

export function ProtectModal({ engineId, instanceId, vitals, engine, mode, onClose }: Props) {
  const toast = useToast()
  const protect = useProtectInstance(engineId, instanceId)
  const unprotect = useUnprotectInstance(engineId, instanceId)
  const action = mode === 'protect' ? protect : unprotect

  const environment = engine?.environment
  const guard = useProdGuard({ reasonRule: { required: true, minLength: 10 }, environment })
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`
  const targetLabel =
    vitals.businessKey !== undefined && vitals.businessKey !== '' ? vitals.businessKey : instanceId

  const problem = action.error?.problem
  const disabled = !guard.reasonOk || action.isPending

  return (
    <ModalShell
      title={mode === 'protect' ? 'Protect this instance' : 'Unprotect this instance'}
      environment={environment}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="primary"
            disabled={disabled}
            title={!guard.reasonOk ? 'a reason of at least 10 characters is required' : undefined}
            onClick={() => {
              action.mutate(
                { reason: guard.reason.trim() },
                {
                  onSuccess: () => {
                    toast({
                      kind: 'success',
                      text:
                        mode === 'protect'
                          ? `${targetLabel} is now protected — ADMIN required for any action below the floor.`
                          : `${targetLabel} is no longer protected.`,
                      auditPath,
                    })
                    onClose()
                  },
                },
              )
            }}
          >
            {action.isPending
              ? mode === 'protect'
                ? 'Protecting…'
                : 'Unprotecting…'
              : mode === 'protect'
                ? `Protect ${targetLabel}`
                : `Unprotect ${targetLabel}`}
          </button>
        </>
      }
    >
      <div className="modal-target">
        <p className="modal-target-heading">
          On <strong>{engine?.name ?? engineId}</strong>:
        </p>
        <ul className="modal-target-list">
          <li>
            Instance <code>{`${engineId}:${instanceId}`}</code>
          </li>
          <li>Status {vitals.status ?? '?'}</li>
        </ul>
      </div>

      {mode === 'protect' ? (
        <p className="strip-note">
          Every verb below the ADMIN floor (retry, suspend, task actions, migrate…) will be refused
          with this reason until an ADMIN unprotects it. The badge appears on the results grid and
          this vitals header.
        </p>
      ) : (
        <p className="strip-note">
          Removes the protection floor — every verb becomes available again to whoever's role
          already grants it. This is itself audited, same as setting the protection was.
        </p>
      )}

      <GuardFields guard={guard} />

      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}
