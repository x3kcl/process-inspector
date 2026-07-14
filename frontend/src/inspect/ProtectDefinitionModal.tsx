// R-SAFE-05 write path (#172): mark/unmark EVERY instance of a process-definition key
// protected in one step, deferred from #165's instance-only shipment. Same shape as
// ProtectModal — BFF-store-only, no typed confirmation, just the reason floor.
import { useProtectDefinition, useUnprotectDefinition } from '../api/protection'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'

interface Props {
  engineId: string
  definitionKey: string
  mode: 'protect' | 'unprotect'
  onClose: () => void
}

export function ProtectDefinitionModal({ engineId, definitionKey, mode, onClose }: Props) {
  const toast = useToast()
  const protect = useProtectDefinition(engineId, definitionKey)
  const unprotect = useUnprotectDefinition(engineId, definitionKey)
  const action = mode === 'protect' ? protect : unprotect

  const guard = useProdGuard({ reasonRule: { required: true, minLength: 10 } })
  const problem = action.error?.problem
  const disabled = !guard.reasonOk || action.isPending

  return (
    <ModalShell
      title={mode === 'protect' ? 'Protect this definition' : 'Unprotect this definition'}
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
                          ? `${definitionKey} is now protected — every instance, present and future, needs ADMIN for any action below the floor.`
                          : `${definitionKey} is no longer protected.`,
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
                ? `Protect ${definitionKey}`
                : `Unprotect ${definitionKey}`}
          </button>
        </>
      }
    >
      <div className="modal-target">
        <p className="modal-target-heading">
          On <strong>{engineId}</strong>:
        </p>
        <ul className="modal-target-list">
          <li>
            Definition key <code>{definitionKey}</code>
          </li>
        </ul>
      </div>

      {mode === 'protect' ? (
        <p className="strip-note">
          Applies to every instance of this key on this engine — present and future deployed
          versions alike — not just the ones currently running. Every verb below the ADMIN floor
          will be refused with this reason until an ADMIN unprotects it.
        </p>
      ) : (
        <p className="strip-note">
          Removes the protection floor for this definition key — every verb becomes available again
          to whoever's role already grants it. This is itself audited, same as setting the
          protection was.
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
