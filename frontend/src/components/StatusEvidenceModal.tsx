// "Explain this status" (R-L3-01, SPEC §3): the falsifiable derivation behind a status chip.
// Read-only. Shows the plan shape chosen and why, per-flag provenance (with a deep link to the
// failing child's Errors & Jobs when a status is FAILED "in subprocess"), and every raw engine
// call the RE-DERIVATION made. The evidence is labeled as re-derived-just-now — the inspector
// never retains the original response bytes, so this is a fresh derivation, not a replay.
import { Link } from 'react-router'
import type { StatusEvidenceFinding, StatusEvidenceLeg } from '../api/model'
import { useInstanceStatusEvidence } from '../inspect/useInstanceQueries'
import { Ts } from '../lib/Ts'
import { ModalShell } from './ModalShell'

interface Props {
  engineId: string
  instanceId: string
  onClose: () => void
}

export function StatusEvidenceModal({ engineId, instanceId, onClose }: Props) {
  const evidence = useInstanceStatusEvidence(engineId, instanceId, true)

  return (
    <ModalShell
      title="Explain this status"
      onClose={onClose}
      footer={
        <button type="button" className="btn" onClick={onClose}>
          Close
        </button>
      }
    >
      <div className="status-evidence">
        {evidence.isPending && <p className="value-muted">Re-deriving from the engine…</p>}
        {evidence.isError && (
          <p role="alert" className="action-hint-warn">
            Could not re-derive the status: {evidence.error.message}
          </p>
        )}
        {evidence.data !== undefined && (
          <>
            <p className="evidence-rederived" role="note">
              <strong>Re-derived just now.</strong>{' '}
              {evidence.data.rederivedAt !== undefined && (
                <>
                  As of <Ts iso={evidence.data.rederivedAt} copyIso />.{' '}
                </>
              )}
              {evidence.data.note}
            </p>

            <section className="evidence-plan">
              <h4>Plan</h4>
              <p>
                <code>{evidence.data.plan}</code> — {evidence.data.planReason}
              </p>
            </section>

            <section className="evidence-findings">
              <h4>Why each flag is what it is</h4>
              <ul>
                {(evidence.data.findings ?? []).map((finding) => (
                  <Finding
                    key={finding.flag}
                    finding={finding}
                    engineId={engineId}
                    onClose={onClose}
                  />
                ))}
              </ul>
            </section>

            <section className="evidence-legs">
              <h4>Engine calls made ({(evidence.data.legs ?? []).length})</h4>
              <div className="evidence-legs-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Leg</th>
                      <th>Call</th>
                      <th>Status</th>
                      <th>Took</th>
                      <th>As of</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(evidence.data.legs ?? []).map((leg, index) => (
                      <LegRow key={index} leg={leg} />
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          </>
        )}
      </div>
    </ModalShell>
  )
}

function Finding({
  finding,
  engineId,
  onClose,
}: {
  finding: StatusEvidenceFinding
  engineId: string
  onClose: () => void
}) {
  return (
    <li className={`evidence-finding ${finding.value === true ? 'flag-on' : 'flag-off'}`}>
      <span className="flag-mark" aria-hidden="true">
        {finding.value === true ? '✓' : '✗'}
      </span>
      <code className="flag-name">{finding.flag}</code>
      <span className="flag-source">⇐ {finding.source}</span>
      {finding.detail !== undefined && <span className="flag-detail">{finding.detail}</span>}
      {finding.deepLinkInstanceId !== undefined && (
        <Link
          className="flag-deeplink"
          to={`/inspect/${engineId}/${finding.deepLinkInstanceId}?tab=errors-jobs`}
          onClick={onClose}
        >
          Open the failing child's Errors &amp; Jobs →
        </Link>
      )}
    </li>
  )
}

function LegRow({ leg }: { leg: StatusEvidenceLeg }) {
  return (
    <tr>
      <td>{leg.label}</td>
      <td>
        <code className="leg-call">
          {leg.method} {leg.url}
        </code>
        {leg.requestBody !== undefined && leg.requestBody !== '' && (
          <code className="leg-body">{leg.requestBody}</code>
        )}
      </td>
      <td>{leg.status ?? '—'}</td>
      <td>{leg.durationMs !== undefined ? `${String(leg.durationMs)} ms` : '—'}</td>
      <td>
        <Ts iso={leg.asOf} />
      </td>
    </tr>
  )
}
