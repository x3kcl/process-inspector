// v2 instance migration — STRICTLY pre-check-first (P0 re-lock). Step 1 picks the target
// version (the definition-versions on-ramp). Step 2 "Check mapping" renders the BFF STATIC
// auto-map pre-check: an Inspector estimate, NOT an engine validation (Flowable's REST API
// has no migration validator). Activities the engine can't auto-map get a targeted from→to
// dropdown; execute is enabled only once the current pre-check is clean. Execute is the one
// real engine call, sends EXACTLY what was pre-checked (+ the compare-and-set digest so a
// move since the pre-check is refused), and is never optimistic — it re-fetches on settle.
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import type { EngineDto, InstanceDetail } from '../api/model'
import { useDefinitionVersions, useMigrateExecute, useMigratePreview } from '../api/migrate'
import type { MigrationPreview, MigrationRequest } from '../api/migrate'
import { VERBS } from '../actions/catalog'
import { businessKeyOrInstanceToken, useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { GuardFields, tokenLabel } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'

interface Props {
  engineId: string
  instanceId: string
  vitals: InstanceDetail
  engine?: EngineDto
  onClose: () => void
}

const meta = VERBS.migrate

export function MigrateModal({ engineId, instanceId, vitals, engine, onClose }: Props) {
  const toast = useToast()
  const definitionKey = vitals.definitionKey
  const currentDefinitionId = vitals.processDefinitionId
  const versions = useDefinitionVersions(engineId, definitionKey)
  const previewM = useMigratePreview(engineId, instanceId)
  const executeM = useMigrateExecute(engineId, instanceId)

  const [targetDefinitionId, setTargetDefinitionId] = useState<string | undefined>(undefined)
  const [mappings, setMappings] = useState<Record<string, string>>({})
  const [dirty, setDirty] = useState(false)
  const [preview, setPreview] = useState<MigrationPreview | null>(null)

  const environment = engine?.environment
  const prod = environment?.toLowerCase() === 'prod'
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`
  // PROD typed-token gate: the business key, else the instance id. Called unconditionally
  // (hooks rule) even though the fields only render in step 2, below.
  const { expectedToken, tokenName } = businessKeyOrInstanceToken(vitals.businessKey, instanceId)
  const guard = useProdGuard({
    reasonRule: { required: true, minLength: 10 },
    environment,
    expectedToken,
  })

  // Default the target to the latest version that is NOT the instance's current one.
  // Memoized so the default-target effect doesn't re-run on every render.
  const targetChoices = useMemo(
    () => (versions.data?.versions ?? []).filter((v) => v.definitionId !== currentDefinitionId),
    [versions.data, currentDefinitionId],
  )
  useEffect(() => {
    if (targetDefinitionId === undefined && targetChoices.length > 0) {
      const latest = targetChoices.find((v) => v.latest === true) ?? targetChoices[0]
      if (latest.definitionId !== undefined) setTargetDefinitionId(latest.definitionId)
    }
  }, [targetChoices, targetDefinitionId])

  const mappingList = () =>
    Object.entries(mappings)
      .filter(([, to]) => to !== '')
      .map(([from, to]) => ({ fromActivityId: from, toActivityId: to }))

  const check = () => {
    if (targetDefinitionId === undefined) return
    const request: MigrationRequest = {
      toDefinitionId: targetDefinitionId,
      mappings: mappingList(),
    }
    previewM.mutate(request, {
      onSuccess: (result) => {
        executeM.reset()
        setPreview(result)
        setDirty(false)
      },
    })
  }

  const remap = (fromActivityId: string, toActivityId: string) => {
    setMappings((prev) => ({ ...prev, [fromActivityId]: toActivityId }))
    setDirty(true)
  }

  // ---- Step 2: the pre-check result (mapping + verify) ----
  if (preview !== null) {
    const flagged = (preview.activities ?? []).filter((a) => a.blocker === true)
    const warnings = (preview.activities ?? []).filter((a) => a.warning === true)
    const targets = preview.targetActivities ?? []

    const { reasonOk, tokenOk } = guard
    const problem = executeM.error?.problem
    // UNKNOWN outcome = the migrate may have reached the engine — never a resubmit.
    const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
    const canExecute =
      preview.executable === true &&
      !dirty &&
      reasonOk &&
      tokenOk &&
      !executeM.isPending &&
      !dispatchedMaybe

    const execute = () => {
      const request: MigrationRequest = {
        toDefinitionId: targetDefinitionId,
        mappings: mappingList(),
        reason: guard.reason.trim(),
        confirmToken: prod ? guard.typed : undefined,
        expectedFromDefinitionId: preview.fromDefinitionId,
        expectedActivityStateDigest: preview.activityStateDigest,
      }
      executeM.mutate(request, {
        onSuccess: (result) => {
          toast({
            kind: 'success',
            text: result.deltaStatement ?? 'Migrated — re-reading the instance.',
            auditPath,
          })
          onClose()
        },
      })
    }

    return (
      <ModalShell
        title="Migrate — check the mapping"
        environment={environment}
        onClose={onClose}
        footer={
          <>
            <button type="button" onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              onClick={() => {
                previewM.reset()
                executeM.reset()
                setPreview(null)
              }}
            >
              ← Change version
            </button>
            {preview.executable !== true || dirty ? (
              <button
                type="button"
                className="primary"
                disabled={previewM.isPending}
                onClick={check}
              >
                {previewM.isPending ? 'Checking…' : 'Re-check mapping'}
              </button>
            ) : (
              <button
                type="button"
                className="danger"
                disabled={!canExecute}
                title={
                  !reasonOk
                    ? 'a reason of at least 10 characters is required'
                    : !tokenOk
                      ? `type the ${tokenName} exactly to enable`
                      : dispatchedMaybe
                        ? 'outcome unknown — re-check the instance instead of resubmitting'
                        : undefined
                }
                onClick={execute}
              >
                {executeM.isPending
                  ? 'Migrating…'
                  : `Migrate ${expectedToken} to v${String(preview.toVersion ?? '?')}`}
              </button>
            )}
          </>
        }
      >
        <p className="verify-sentence">{preview.summary ?? 'The BFF returned no plan summary.'}</p>

        {/* The honesty banner — NEVER claims the engine checked this (engineValidated=false). */}
        <div className="callout callout-amber" role="alert">
          {preview.banner ??
            'Inspector pre-check — this is not a Flowable validation. The engine checks this only when you execute.'}
        </div>

        {flagged.length > 0 && (
          <div className="modal-field">
            <span className="verify-red">
              {String(flagged.length)} active activit
              {flagged.length === 1 ? 'y' : 'ies'} can’t be auto-mapped — pick a target for each:
            </span>
            <table className="mapping-table">
              <tbody>
                {flagged.map((a) => (
                  <tr key={a.fromActivityId}>
                    <td>
                      <code>{a.fromActivityId}</code>
                      {a.fromType !== undefined && (
                        <span className="value-muted"> · {a.fromType}</span>
                      )}
                    </td>
                    <td aria-hidden>→</td>
                    <td>
                      <select
                        aria-label={`target for ${a.fromActivityId ?? ''}`}
                        value={mappings[a.fromActivityId ?? ''] ?? ''}
                        onChange={(e) => {
                          remap(a.fromActivityId ?? '', e.target.value)
                        }}
                      >
                        <option value="">choose target…</option>
                        {targets.map((t) => (
                          <option key={t.id} value={t.id ?? ''}>
                            {t.name !== undefined && t.name !== ''
                              ? `${t.name} (${t.id ?? ''})`
                              : t.id}
                            {t.type !== undefined && ` · ${t.type}`}
                          </option>
                        ))}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {warnings.length > 0 && (
          <div className="callout callout-amber" role="alert">
            <ul className="warning-list">
              {warnings.map((w) => (
                <li key={w.fromActivityId}>
                  <strong>{w.fromActivityId}: </strong>
                  {w.detail}
                </li>
              ))}
            </ul>
          </div>
        )}

        <p className="reversibility-line">
          <span className={`reversibility rev-${meta.reversibility.toLowerCase()}`}>
            {meta.reversibility}
          </span>{' '}
          {meta.reversibilityNote}
          {preview.callActivityChildCount !== undefined && preview.callActivityChildCount > 0 && (
            <>
              {' '}
              <strong>
                +{String(preview.callActivityChildCount)} child instance(s) are NOT migrated
              </strong>{' '}
              (they keep their own definition).
            </>
          )}
        </p>

        {prod && (
          <div className="callout callout-prod" role="alert">
            This is a PRODUCTION engine.
          </div>
        )}

        <details className="exact-request">
          <summary>exact engine request</summary>
          <pre className="value-body">{JSON.stringify(preview.restBody ?? {}, null, 2)}</pre>
          <p className="value-muted">
            {preview.method ?? 'POST'} {preview.enginePath ?? ''} — the engine applies (or rejects)
            the whole document atomically.
          </p>
        </details>

        <GuardFields
          guard={guard}
          expectedToken={expectedToken}
          tokenFieldLabel={tokenLabel(tokenName, expectedToken, 'migrate')}
        />

        {problem !== undefined && (
          <div className="error-banner" role="alert">
            {problemBanner(problem)}
          </div>
        )}
      </ModalShell>
    )
  }

  // ---- Step 1: pick the target version ----
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
            disabled={targetDefinitionId === undefined || previewM.isPending}
            onClick={check}
          >
            {previewM.isPending ? 'Checking…' : 'Check mapping →'}
          </button>
        </>
      }
    >
      <p className="modal-target-heading">
        Instance <code>{`${engineId}:${instanceId}`}</code>
        {vitals.definitionVersion !== undefined && (
          <>
            {' '}
            · currently on <code>v{String(vitals.definitionVersion)}</code>
          </>
        )}
      </p>
      <p className="strip-note">
        Nothing is migrated in this step — “Check mapping” compares the two versions’ activities.
        Flowable does the real check only when you execute.
      </p>

      <div className="modal-field">
        <span>Target version</span>
        {versions.isPending && <p className="zero-state">Loading deployed versions…</p>}
        {versions.isError && (
          <p className="strip-note">The version list could not be loaded — close and retry.</p>
        )}
        {versions.isSuccess && targetChoices.length === 0 && (
          <p className="zero-state">
            No other deployed version of <code>{definitionKey}</code> to migrate to.
          </p>
        )}
        {targetChoices.length > 0 && (
          <select
            aria-label="target version"
            value={targetDefinitionId ?? ''}
            onChange={(e) => {
              // A new target version invalidates the pre-check AND any mappings made for the
              // old target (a stale mapping must never carry to a different version).
              setTargetDefinitionId(e.target.value)
              setPreview(null)
              setMappings({})
              setDirty(false)
            }}
          >
            {targetChoices.map((v) => (
              <option key={v.definitionId} value={v.definitionId ?? ''}>
                v{String(v.version ?? '?')}
                {v.latest === true ? ' (latest)' : ''} · {String(v.runningInstanceCount ?? 0)}{' '}
                running
              </option>
            ))}
          </select>
        )}
        {definitionKey !== undefined && definitionKey !== '' && (
          <p className="strip-note">
            <Link to={`/definitions/${engineId}/${encodeURIComponent(definitionKey)}/versions`}>
              See all versions &amp; running counts ↗
            </Link>
          </p>
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
