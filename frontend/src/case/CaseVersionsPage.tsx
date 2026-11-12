// The case migration on-ramp (#500): every deployed version of a case key with its RUNNING and
// ended case counts. Per version: the filtered cohort door (6.8+), and the two by-definition
// doors that need Flowable 7.x — on an older engine those are greyed with the reason the BFF
// would refuse with (version gate), never hidden and never faked.
import { useState } from 'react'
import { useParams } from 'react-router'
import { useCaseDefinitionVersions, useCaseMigrationBatches } from '../api/caseMigration'
import type { CaseDefinitionDoor, CaseDefinitionVersion } from '../api/caseMigration'
import { roleOn, useMe } from '../api/me'
import { ActionHint } from '../components/ActionHint'
import { Breadcrumbs } from '../shell/Breadcrumbs'
import { definitionVersionCrumbs } from '../shell/breadcrumbs'
import { CaseCohortMigrateModal } from './CaseCohortMigrateModal'
import { CaseDefinitionMigrateModal } from './CaseDefinitionMigrateModal'

const GATE_HINT = 'case-definition-routes-gate'
const ROLE_HINT = 'case-migrate-role'

export function CaseVersionsPage() {
  const { engineId, key } = useParams<{ engineId: string; key: string }>()
  const versions = useCaseDefinitionVersions(engineId ?? '', key)
  const me = useMe()
  const admin = roleOn(me.data, engineId ?? '') === 'ADMIN'
  const [cohortFrom, setCohortFrom] = useState<string | undefined>(undefined)
  const [door, setDoor] = useState<
    { door: CaseDefinitionDoor; from: CaseDefinitionVersion } | undefined
  >(undefined)
  const [batchesOf, setBatchesOf] = useState<string | undefined>(undefined)
  const gate = versions.data?.definitionRoutesGate ?? undefined
  const rows = versions.data?.versions ?? []
  const hasTarget = rows.length > 1

  return (
    <div className="page">
      <Breadcrumbs
        segments={definitionVersionCrumbs({ engineId: engineId ?? '', definitionKey: key ?? '' })}
      />
      <header className="page-header">
        <h2>
          Case versions of <code>{key}</code>
        </h2>
        <p className="muted">
          Deployed versions on <code>{engineId}</code> with their running and ended case counts —
          the case migration cohort.
        </p>
        {gate !== undefined && (
          <p id={GATE_HINT} className="strip-note" role="note">
            Batch migration by definition and historic migration: {gate}. A single running case and
            a filtered cohort migrate on this engine.
          </p>
        )}
        {!admin && <ActionHint id={ROLE_HINT} text="Case migration needs ADMIN on this engine." />}
      </header>

      {cohortFrom != null && engineId !== undefined && (
        <CaseCohortMigrateModal
          engineId={engineId}
          fromCaseDefinitionId={cohortFrom}
          versions={rows}
          containsGate={gate}
          onClose={() => {
            setCohortFrom(undefined)
          }}
        />
      )}
      {door != null && engineId !== undefined && (
        <CaseDefinitionMigrateModal
          engineId={engineId}
          door={door.door}
          from={door.from}
          versions={rows}
          onClose={() => {
            setDoor(undefined)
          }}
        />
      )}

      {versions.isPending && <p className="muted">Loading versions…</p>}
      {versions.isError && (
        <p className="error-banner" role="alert">
          {versions.error.status === 404
            ? `No deployed version of case '${key ?? ''}' on this engine.`
            : 'The case version list could not be loaded.'}
        </p>
      )}

      {versions.isSuccess && (
        <>
          {versions.data.complete === false && (
            <p className="strip-note">
              Showing the newest {String(rows.length)} of {String(versions.data.totalVersions ?? 0)}{' '}
              deployed versions.
            </p>
          )}
          <table className="versions-table">
            <thead>
              <tr>
                <th>Version</th>
                <th>Name</th>
                <th className="num">Running</th>
                <th className="num">Ended</th>
                <th>Migrate</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((v) => {
                const running = v.runningCaseCount ?? 0
                const ended = v.endedCaseCount ?? 0
                const gated = gate !== undefined
                const describedBy = gated ? GATE_HINT : admin ? undefined : ROLE_HINT
                return (
                  <tr key={v.caseDefinitionId}>
                    <td>
                      v{String(v.version ?? '?')}
                      {v.latest === true && <span className="badge badge-latest"> latest</span>}
                    </td>
                    <td>{v.name ?? '—'}</td>
                    <td className="num">{String(running)}</td>
                    <td className="num">{String(ended)}</td>
                    <td>
                      <button
                        type="button"
                        className="link-button"
                        disabled={!admin || !hasTarget || running === 0}
                        aria-describedby={admin ? undefined : ROLE_HINT}
                        onClick={() => {
                          setCohortFrom(v.caseDefinitionId)
                        }}
                      >
                        Migrate cases…
                      </button>{' '}
                      <button
                        type="button"
                        className="link-button"
                        disabled={gated || !admin || !hasTarget || running === 0}
                        aria-describedby={describedBy}
                        title={gated ? gate : undefined}
                        onClick={() => {
                          setDoor({ door: 'batch', from: v })
                        }}
                      >
                        Batch-migrate all running…
                      </button>{' '}
                      <button
                        type="button"
                        className="link-button"
                        disabled={gated || !admin || !hasTarget || ended === 0}
                        aria-describedby={describedBy}
                        title={gated ? gate : undefined}
                        onClick={() => {
                          setDoor({ door: 'historic', from: v })
                        }}
                      >
                        Migrate ended cases…
                      </button>{' '}
                      {!gated && (
                        <button
                          type="button"
                          className="link-button"
                          onClick={() => {
                            setBatchesOf(
                              batchesOf === v.caseDefinitionId ? undefined : v.caseDefinitionId,
                            )
                          }}
                        >
                          Batches
                        </button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {batchesOf !== undefined && engineId !== undefined && (
            <CaseBatches engineId={engineId} caseDefinitionId={batchesOf} />
          )}
        </>
      )}
    </div>
  )
}

/** The engine's own batch rows: a batch answers 200 before any case moved, so this is the outcome. */
function CaseBatches({
  engineId,
  caseDefinitionId,
}: {
  engineId: string
  caseDefinitionId: string
}) {
  const batches = useCaseMigrationBatches(engineId, caseDefinitionId)
  if (batches.isPending) return <p className="muted">Loading batches…</p>
  if (batches.isError)
    return (
      <p className="error-banner" role="alert">
        The batch list could not be loaded.
      </p>
    )
  if (batches.data.length === 0)
    return <p className="muted">No case-migration batch started from this version.</p>
  return (
    <table className="versions-table" aria-label="Case migration batches">
      <thead>
        <tr>
          <th>Batch</th>
          <th>Status</th>
          <th>Started</th>
          <th>Target</th>
          <th>Cases by part status</th>
        </tr>
      </thead>
      <tbody>
        {batches.data.map((batch) => (
          <tr key={batch.batchId}>
            <td>
              <code>{batch.batchId}</code>
            </td>
            <td>{batch.status ?? '—'}</td>
            <td>{batch.createTime ?? '—'}</td>
            <td>
              <code>{batch.toCaseDefinitionId ?? '—'}</code>
            </td>
            <td>
              {Object.entries(batch.partsByStatus ?? {})
                .map(([status, n]) => `${status} ${String(n)}`)
                .join(' · ') || '—'}
              {(batch.notSucceededCaseIds ?? []).length > 0 &&
                ` · not succeeded: ${(batch.notSucceededCaseIds ?? []).join(', ')}`}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
