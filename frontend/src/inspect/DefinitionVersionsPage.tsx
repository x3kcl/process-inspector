// The migration on-ramp view (P0 re-lock: cohort visibility in slice-1) — every deployed
// version of a process key with its RUNNING instance count ("37 running on v3 · latest v5").
// Read-only; the entry point that answers "how bad is this bad deploy, how many are wedged,
// on which version" before an operator migrates anything. Per-version rows deep-link to a
// search scoped to that version.
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useDefinitionVersions } from '../api/migrate'
import { roleOn, useMe } from '../api/me'
import { ActionHint } from '../components/ActionHint'
import { ProtectDefinitionModal } from './ProtectDefinitionModal'

export function DefinitionVersionsPage() {
  const { engineId, key } = useParams<{ engineId: string; key: string }>()
  const versions = useDefinitionVersions(engineId ?? '', key)
  const me = useMe()
  const roleHint = roleOn(me.data, engineId ?? '')
  const [protectOpen, setProtectOpen] = useState(false)
  const isProtected = versions.data?.protectedDefinition === true

  return (
    <div className="page">
      <header className="page-header">
        <h2>
          Versions of <code>{key}</code>
        </h2>
        <p className="muted">
          Deployed versions on <code>{engineId}</code> with their running-instance counts — the
          migration cohort. Read-only.
        </p>
        {versions.isSuccess && (
          <div className="definition-protection-strip">
            {/* #172: definition-key scope, deferred from #165 — protects EVERY instance of this
                key, present and future, not just the ones currently listed here. */}
            {isProtected && (
              <span
                className="protected-badge"
                role="status"
                title={
                  versions.data.protectionReason !== undefined &&
                  versions.data.protectionReason !== ''
                    ? `Protected (R-SAFE-05): ${versions.data.protectionReason} — L3 (ADMIN) action required`
                    : 'Protected (R-SAFE-05) — L3 (ADMIN) action required'
                }
              >
                🔒 Protected
              </span>
            )}
            <button
              type="button"
              className="protect-toggle"
              disabled={roleHint !== 'ADMIN'}
              aria-describedby={roleHint === 'ADMIN' ? undefined : 'protect-definition-hint'}
              onClick={() => {
                setProtectOpen(true)
              }}
            >
              {isProtected ? 'Unprotect this definition…' : '🔒 Protect this definition…'}
            </button>
            {roleHint !== 'ADMIN' && (
              <ActionHint
                id="protect-definition-hint"
                text="Marking or unmarking protection needs ADMIN on this engine."
              />
            )}
          </div>
        )}
      </header>

      {protectOpen && engineId !== undefined && key !== undefined && (
        <ProtectDefinitionModal
          engineId={engineId}
          definitionKey={key}
          mode={isProtected ? 'unprotect' : 'protect'}
          onClose={() => {
            setProtectOpen(false)
          }}
        />
      )}

      {versions.isPending && <p className="muted">Loading versions…</p>}
      {versions.isError && (
        <p className="error-banner" role="alert">
          {versions.error.status === 404
            ? `No deployed version of '${key ?? ''}' on this engine.`
            : 'The version list could not be loaded.'}
        </p>
      )}

      {versions.isSuccess && (
        <>
          {versions.data.complete === false && (
            <p className="strip-note">
              Showing the newest {String(versions.data.versions?.length ?? 0)} of{' '}
              {String(versions.data.totalVersions ?? 0)} deployed versions.
            </p>
          )}
          <table className="versions-table">
            <thead>
              <tr>
                <th>Version</th>
                <th>Name</th>
                <th className="num">Running</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {(versions.data.versions ?? []).map((v) => (
                <tr key={v.definitionId}>
                  <td>
                    v{String(v.version ?? '?')}
                    {v.latest === true && <span className="badge badge-latest"> latest</span>}
                  </td>
                  <td>{v.name ?? '—'}</td>
                  <td className="num">{String(v.runningInstanceCount ?? 0)}</td>
                  <td>
                    {(v.runningInstanceCount ?? 0) > 0 && (
                      <Link
                        className="link-button"
                        to={`/search?engineId=${engineId ?? ''}&processDefinitionId=${encodeURIComponent(v.definitionId ?? '')}`}
                      >
                        View instances
                      </Link>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}
