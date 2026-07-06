// v1.1 change-state — STRICTLY simulation-first (SPEC §5.2). Step 1 (intent) only
// collects source/target nodes; its single submit is "Simulate move", which renders the
// BFF's preview: the plan sentence, gateway warnings, the honesty note, and the exact
// engine REST payload. Step 2 (verify) is the only place execute can be dispatched, and
// it sends the EXACT request that was simulated — changing the selection drops the
// preview and forces a re-simulate. Never optimistic: the execute mutation re-fetches
// every instance segment + audit on settle (corrective-actions §4).
import { useState } from 'react'
import type { EngineDto, InstanceDetail } from '../api/model'
import { useChangeStateExecute, useChangeStatePreview } from '../api/surgery'
import type { ChangeStatePreview, ChangeStateRequest } from '../api/surgery'
import { VERBS } from '../actions/catalog'
import { problemBanner } from '../actions/problem'
import type { ActionProblem } from '../actions/problem'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'
import { useInstanceDiagram } from '../inspect/useInstanceQueries'
import { activityLabel, parseActivityCatalog } from './activityCatalog'

interface Props {
  engineId: string
  instanceId: string
  vitals: InstanceDetail
  engine?: EngineDto
  onClose: () => void
}

interface Simulated {
  /** The request that produced the preview — execute sends exactly this (+ reason). */
  request: ChangeStateRequest
  preview: ChangeStatePreview
}

export function ChangeStateModal({ engineId, instanceId, vitals, engine, onClose }: Props) {
  const toast = useToast()
  const diagram = useInstanceDiagram(engineId, instanceId)
  const previewM = useChangeStatePreview(engineId, instanceId)
  const executeM = useChangeStateExecute(engineId, instanceId)

  const [sources, setSources] = useState<string[]>([])
  const [targets, setTargets] = useState<string[]>([])
  const [targetFilter, setTargetFilter] = useState('')
  const [simulated, setSimulated] = useState<Simulated | null>(null)

  const environment = engine?.environment
  const active = vitals.currentActivities ?? []
  const catalog = parseActivityCatalog(diagram.data?.xml)
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`

  const toggle = (list: string[], set: (next: string[]) => void, id: string) => {
    set(list.includes(id) ? list.filter((entry) => entry !== id) : [...list, id])
  }

  const simulate = () => {
    const request: ChangeStateRequest = { sourceActivityIds: sources, targetActivityIds: targets }
    previewM.mutate(request, {
      onSuccess: (preview) => {
        executeM.reset()
        setSimulated({ request, preview })
      },
    })
  }

  if (simulated !== null) {
    return (
      <VerifyMoveStep
        instanceId={instanceId}
        vitals={vitals}
        engine={engine}
        simulated={simulated}
        pending={executeM.isPending}
        problem={executeM.error?.problem}
        onExecute={(reason) => {
          executeM.mutate(
            { ...simulated.request, reason },
            {
              onSuccess: (result) => {
                toast({
                  kind: 'success',
                  text: result.deltaStatement ?? 'Token moved — re-reading the instance.',
                  auditPath,
                })
                onClose()
              },
            },
          )
        }}
        onBack={() => {
          // Back to intent: the simulation no longer matches what the user may select
          // next, so it is dropped — simulation-first means no stale preview survives.
          executeM.reset()
          setSimulated(null)
        }}
        onClose={onClose}
      />
    )
  }

  const meta = VERBS.changeState
  const filtered =
    targetFilter.trim() === ''
      ? catalog
      : catalog.filter((activity) =>
          activityLabel(activity).toLowerCase().includes(targetFilter.trim().toLowerCase()),
        )

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
            disabled={sources.length === 0 || targets.length === 0 || previewM.isPending}
            title={
              sources.length === 0
                ? 'pick at least one source node to cancel'
                : targets.length === 0
                  ? 'pick at least one target node to start'
                  : undefined
            }
            onClick={simulate}
          >
            {previewM.isPending
              ? 'Simulating…'
              : `Simulate move (${String(sources.length)} → ${String(targets.length)})`}
          </button>
        </>
      }
    >
      <p className="modal-target-heading">
        Instance <code>{`${engineId}:${instanceId}`}</code>
        {vitals.businessKey !== undefined && vitals.businessKey !== '' && (
          <>
            {' '}
            · business key <code>{vitals.businessKey}</code>
          </>
        )}
      </p>
      <p className="strip-note">
        Nothing is sent to the engine in this step — “Simulate move” renders the exact plan for
        verification first.
      </p>

      <div className="modal-field">
        <span>Source node(s) — the token is CANCELED here</span>
        {active.length === 0 ? (
          <p className="zero-state">No currently active activities — there is no token to move.</p>
        ) : (
          <div className="activity-checklist" role="group" aria-label="source nodes">
            {active.map((current) => {
              const id = current.activityId ?? ''
              return (
                <label key={id}>
                  <input
                    type="checkbox"
                    checked={sources.includes(id)}
                    onChange={() => {
                      toggle(sources, setSources, id)
                    }}
                  />
                  {current.activityName !== undefined && current.activityName !== ''
                    ? `${current.activityName} (${id})`
                    : id}
                  {current.activityType !== undefined && ` · ${current.activityType}`}
                </label>
              )
            })}
          </div>
        )}
      </div>

      <div className="modal-field">
        <span>Target node(s) — a fresh token STARTS here</span>
        <input
          type="search"
          placeholder="filter nodes…"
          value={targetFilter}
          onChange={(event) => {
            setTargetFilter(event.target.value)
          }}
        />
        {diagram.isPending && <p className="zero-state">Loading the definition’s nodes…</p>}
        {diagram.isError && (
          <p className="strip-note">
            The diagram could not be loaded, so the node catalog is unavailable — close and retry
            once the engine answers.
          </p>
        )}
        {diagram.isSuccess && catalog.length === 0 && (
          <p className="zero-state">No flow nodes found in the deployed definition XML.</p>
        )}
        {catalog.length > 0 && (
          <div className="activity-checklist" role="group" aria-label="target nodes">
            {filtered.map((activity) => (
              <label key={activity.id}>
                <input
                  type="checkbox"
                  checked={targets.includes(activity.id)}
                  onChange={() => {
                    toggle(targets, setTargets, activity.id)
                  }}
                />
                {activityLabel(activity)}
              </label>
            ))}
            {filtered.length === 0 && <p className="zero-state">No nodes match the filter.</p>}
          </div>
        )}
      </div>

      {previewM.error !== null && (
        <div className="error-banner" role="alert">
          {problemBanner(previewM.error.problem)}
        </div>
      )}
    </ModalShell>
  )
}

function VerifyMoveStep({
  instanceId,
  vitals,
  engine,
  simulated,
  pending,
  problem,
  onExecute,
  onBack,
  onClose,
}: {
  instanceId: string
  vitals: InstanceDetail
  engine?: EngineDto
  simulated: Simulated
  pending: boolean
  problem?: ActionProblem
  onExecute: (reason: string) => void
  onBack: () => void
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const [typed, setTyped] = useState('')
  const environment = engine?.environment
  const prod = environment?.toLowerCase() === 'prod'
  const preview = simulated.preview

  // PROD typed-token gate (corrective-actions §3): the business key, else the instance id.
  const hasBusinessKey = vitals.businessKey !== undefined && vitals.businessKey !== ''
  const expectedToken = hasBusinessKey ? (vitals.businessKey ?? instanceId) : instanceId
  const tokenName = hasBusinessKey ? 'business key' : 'instance id'
  const targetLabel = expectedToken

  const reasonOk = reason.trim().length >= 10
  const tokenOk = !prod || typed === expectedToken
  // UNKNOWN outcome = the move may have reached the engine — never a resubmit (§4).
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
  const disabled = !reasonOk || !tokenOk || pending || dispatchedMaybe

  return (
    <ModalShell
      title="Verify the move — simulation"
      environment={environment}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button type="button" onClick={onBack}>
            ← Change selection (re-simulate)
          </button>
          <button
            type="button"
            className="danger"
            disabled={disabled}
            title={
              !reasonOk
                ? 'a reason of at least 10 characters is required'
                : !tokenOk
                  ? `type the ${tokenName} exactly to enable`
                  : dispatchedMaybe
                    ? 'outcome unknown — re-check the instance instead of resubmitting'
                    : undefined
            }
            onClick={() => {
              onExecute(reason.trim())
            }}
          >
            {pending ? 'Executing…' : `Execute move on ${targetLabel}`}
          </button>
        </>
      }
    >
      <p className="verify-sentence">{preview.summary ?? 'The BFF returned no plan summary.'}</p>

      {preview.warnings !== undefined && preview.warnings.length > 0 && (
        <div className="callout callout-amber" role="alert">
          <ul className="warning-list">
            {preview.warnings.map((warning, index) => (
              <li key={warning.code ?? String(index)}>
                {warning.code !== undefined && <strong>{warning.code}: </strong>}
                {warning.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {prod && (
        <div className="callout callout-prod" role="alert">
          This is a PRODUCTION engine.
        </div>
      )}

      {/* §5.2 honesty note: a BFF-calculated preview, not an engine-verified dry-run. */}
      <p className="simulation-note strip-note">
        {preview.simulationNote ??
          'This preview was calculated by the inspector, not verified by the engine.'}
      </p>

      <details className="exact-request">
        <summary>exact engine request</summary>
        <pre className="value-body">{JSON.stringify(preview.payload ?? {}, null, 2)}</pre>
        <p className="value-muted">
          {preview.method ?? 'POST'} {preview.enginePath ?? ''} — cancelActivityIds are canceled and
          startActivityIds started in ONE engine transaction.
        </p>
      </details>

      <label className="modal-field">
        Reason (required, at least 10 characters — lands in the audit trail)
        <textarea
          value={reason}
          rows={2}
          maxLength={2000}
          onChange={(event) => {
            setReason(event.target.value)
          }}
        />
      </label>

      {prod && (
        <label className="modal-field">
          Type the {tokenName} <code>{expectedToken}</code> to enable the execute button
          <input
            type="text"
            value={typed}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setTyped(event.target.value)
            }}
          />
        </label>
      )}

      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}
