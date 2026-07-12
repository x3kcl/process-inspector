// v1.1 restart-as-new — resurrect an ENDED instance as a fresh one (SPEC §5.2). The
// version fork is a mandatory, un-defaulted choice (pin the original definition version
// vs float to latest): the operator must state intent before the button unlocks. After
// execute the modal REPLACES its content with the honesty report — the new instance id
// and exactly which variables were carried vs skipped (name → reason) — because there is
// no preview endpoint for restart and no optimistic state anywhere.
import { useState } from 'react'
import { Link } from 'react-router'
import type { EngineDto, InstanceDetail } from '../api/model'
import { useRestartInstance } from '../api/surgery'
import type { RestartInstanceResult } from '../api/surgery'
import { VERBS } from '../actions/catalog'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { Segmented } from '../components/Segmented'
import { useToast } from '../components/toast'

interface Props {
  engineId: string
  instanceId: string
  vitals: InstanceDetail
  engine?: EngineDto
  onClose: () => void
}

type VersionChoice = 'pin' | 'latest'

export function RestartModal({ engineId, instanceId, vitals, engine, onClose }: Props) {
  const toast = useToast()
  const restart = useRestartInstance(engineId, instanceId)
  const [choice, setChoice] = useState<VersionChoice | undefined>(undefined)
  const [result, setResult] = useState<RestartInstanceResult | null>(null)

  const meta = VERBS.restartAsNew
  const environment = engine?.environment
  const prod = environment?.toLowerCase() === 'prod'
  const guard = useProdGuard({ reasonRule: { required: true, minLength: 10 }, environment })
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`
  const targetLabel =
    vitals.businessKey !== undefined && vitals.businessKey !== '' ? vitals.businessKey : instanceId

  // "v46" for the pin option: the detail DTO carries the version; fall back to the
  // middle segment of processDefinitionId (key:version:uuid) when it is absent.
  const version =
    vitals.definitionVersion !== undefined
      ? String(vitals.definitionVersion)
      : (vitals.processDefinitionId?.split(':')[1] ?? '?')

  const problem = restart.error?.problem
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
  const disabled = choice === undefined || !guard.reasonOk || restart.isPending || dispatchedMaybe

  if (result !== null) {
    const skipped = Object.entries(result.skippedVariables ?? {})
    const carried = result.carriedVariables ?? []
    return (
      <ModalShell
        title="Restarted — what was carried over"
        environment={environment}
        onClose={onClose}
        footer={
          <>
            <button type="button" onClick={onClose}>
              Close
            </button>
            {result.newProcessInstanceId !== undefined && (
              <Link
                className="primary link-button"
                to={`/inspect/${engineId}/${encodeURIComponent(result.newProcessInstanceId)}`}
                onClick={onClose}
              >
                Open the new instance
              </Link>
            )}
          </>
        }
      >
        <p className="verify-sentence">
          {result.deltaStatement ?? 'The instance was restarted as a new instance.'}
        </p>
        <ul className="modal-target-list">
          <li>
            New instance <code>{result.newProcessInstanceId ?? '?'}</code>
          </li>
          <li>
            Definition <code>{result.processDefinitionId ?? '?'}</code>
          </li>
          <li>
            {String(carried.length)} variable{carried.length === 1 ? '' : 's'} carried over
            {carried.length > 0 && (
              <>
                : <code>{carried.join(', ')}</code>
              </>
            )}
          </li>
        </ul>
        {skipped.length === 0 ? (
          <p className="strip-note">No variables were skipped — nothing was left behind.</p>
        ) : (
          <div className="callout callout-amber" role="alert">
            <p className="skipped-heading">
              {String(skipped.length)} variable{skipped.length === 1 ? ' was' : 's were'} NOT
              carried to the new instance:
            </p>
            <table className="skipped-variables">
              <thead>
                <tr>
                  <th scope="col">variable</th>
                  <th scope="col">why it was left behind</th>
                </tr>
              </thead>
              <tbody>
                {skipped.map(([name, why]) => (
                  <tr key={name}>
                    <td>
                      <code>{name}</code>
                    </td>
                    <td>{why}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="value-muted">
          The restart is attributed in the ORIGINAL instance’s audit trail (Audit &amp; Notes tab).
        </p>
      </ModalShell>
    )
  }

  return (
    <ModalShell
      title={`${meta.label} — ${meta.plain}`}
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
            title={
              choice === undefined
                ? 'choose which definition version the new instance starts on'
                : !guard.reasonOk
                  ? 'a reason of at least 10 characters is required'
                  : dispatchedMaybe
                    ? 'outcome unknown — re-check the audit trail instead of resubmitting'
                    : undefined
            }
            onClick={() => {
              restart.mutate(
                { pinDefinitionVersion: choice === 'pin', reason: guard.reason.trim() },
                {
                  onSuccess: (restarted) => {
                    setResult(restarted)
                    toast({
                      kind: 'success',
                      text:
                        restarted.deltaStatement ?? `Restarted ${targetLabel} as a new instance.`,
                      auditPath,
                    })
                  },
                },
              )
            }}
          >
            {restart.isPending
              ? 'Restarting…'
              : `Restart ${targetLabel} as a new instance${choice === 'pin' ? ` on v${version}` : choice === 'latest' ? ' on the latest version' : ''}`}
          </button>
        </>
      }
    >
      <p className="modal-verb-badges">
        <span className={`reversibility rev-${meta.reversibility.toLowerCase()}`}>
          {meta.reversibility}
        </span>{' '}
        {meta.reversibilityNote}
      </p>
      <div className="modal-target">
        <p className="modal-target-heading">
          On <strong>{engine?.name ?? engineId}</strong>
          {prod && ' — a PRODUCTION engine'}:
        </p>
        <ul className="modal-target-list">
          <li>
            Ended instance <code>{`${engineId}:${instanceId}`}</code>
          </li>
          <li>
            Definition{' '}
            {vitals.definitionName ?? vitals.definitionKey ?? vitals.processDefinitionId ?? '?'} v
            {version}
          </li>
          <li>Status {vitals.status ?? '?'}</li>
          {vitals.businessKey !== undefined && vitals.businessKey !== '' && (
            <li>
              Business key <code>{vitals.businessKey}</code>
            </li>
          )}
        </ul>
      </div>

      <div className="modal-field">
        <span>Definition version for the new instance (choose one — no default)</span>
        <Segmented<VersionChoice>
          ariaLabel="definition version for the new instance"
          value={choice}
          onChange={setChoice}
          options={[
            {
              value: 'pin',
              label: `Pin to original version (start on v${version})`,
              title: 'the new instance runs the exact definition this one ran',
            },
            {
              value: 'latest',
              label: 'Use latest deployed version',
              title: 'the new instance runs whatever version is currently deployed',
            },
          ]}
        />
      </div>

      <p className="strip-note">
        Portable case variables are copied to the new instance; engine-intrinsic and non-portable
        values are skipped — the exact skipped list (with reasons) is shown after the restart.
      </p>

      {prod && (
        <div className="callout callout-prod" role="alert">
          This is a PRODUCTION engine — the new instance starts running immediately.
        </div>
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
