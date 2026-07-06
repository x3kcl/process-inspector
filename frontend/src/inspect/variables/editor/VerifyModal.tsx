// §4a verification — the one modal in the edit flow, user-initiated. Sentence and
// payload are derived from the SAME request object so they can never disagree. Scalars
// diff as Current → After panes; json as the structural path diff (formatting noise
// never shows). Fixed-order warnings (type change · PROD), freshness re-check on open,
// collapsed exact-request expander, reason per the §6 tier rules, a confirm button that
// RESTATES the change, and the CAS-conflict replacement panel with no overwrite-anyway.
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { ActionRequest } from '../../../api/actions'
import type { EngineDto } from '../../../api/model'
import { fetchInstanceVariable } from '../../../api/queries'
import { reasonRule, reasonValid } from '../../../actions/catalog'
import type { ActionProblem } from '../../../actions/problem'
import { problemBanner } from '../../../actions/problem'
import { ModalShell } from '../../../components/ModalShell'
import { formatBytes, serializedBytes } from '../ledger'
import { changeSentence, changeLine, countLeaves, diffSummary, short, structuralDiff } from './diff'

interface Props {
  engineId: string
  instanceId: string
  engine?: EngineDto
  /** Business key when present, else the instance id — the sentence's target. */
  targetLabel: string
  /** The exact payload to be sent (variable.name/type/value/expectedOldValue). */
  request: ActionRequest
  /** Plain-language type label for the sentence ("number", "structured (json)"). */
  typeLabel: string
  /** The §4a amber callout: the operator unlocked and changed the declared type. */
  typeChanged: { from: string; to: string } | null
  pending: boolean
  problem?: ActionProblem
  onDispatch: (reason: string | undefined) => void
  /** CAS conflict / staleness forward path: re-seed the editor from the engine value. */
  onStartOver: (currentValue: unknown) => void
  onClose: () => void
}

export function VerifyModal({
  engineId,
  instanceId,
  engine,
  targetLabel,
  request,
  typeLabel,
  typeChanged,
  pending,
  problem,
  onDispatch,
  onStartOver,
  onClose,
}: Props) {
  const [reason, setReason] = useState('')
  const variable = request.variable
  const name = variable?.name ?? '?'
  const before = variable?.expectedOldValue
  const after = variable?.value
  const environment = engine?.environment
  const prod = environment?.toLowerCase() === 'prod'
  const rule = reasonRule(1, environment)

  // Freshness re-check on open (§4a): the server value is re-read once; a drift blocks
  // the confirm with reload as the only forward path — never a silent overwrite race.
  const fresh = useQuery({
    queryKey: ['verify-freshness', engineId, instanceId, name],
    queryFn: () => fetchInstanceVariable({ engineId, instanceId }, name),
    staleTime: 0,
    gcTime: 0,
    refetchOnMount: 'always',
  })
  const freshness: 'checking' | 'fresh' | 'stale' | 'unknown' = fresh.isPending
    ? 'checking'
    : fresh.isError
      ? 'unknown'
      : sameValue(fresh.data.value, before)
        ? 'fresh'
        : 'stale'

  const structured =
    (before !== null && typeof before === 'object') || (after !== null && typeof after === 'object')
  const changes = useMemo(() => structuralDiff(before, after), [before, after])
  const sentence = changeSentence({
    name,
    before,
    after,
    typeLabel,
    scopeLabel: 'applies to the whole case',
    targetLabel,
    engineName: engine?.name ?? engineId,
    environment,
  })
  const byteDelta = serializedBytes(after) - serializedBytes(before)
  const confirmLabel = structured
    ? `Change ${name} (${String(changes.length)} field${changes.length === 1 ? '' : 's'}) on ${targetLabel}`
    : `Change ${name} from ${short(before)} to ${short(after)} on ${targetLabel}`

  const casConflict = problem?.code === 'cas-conflict'
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
  const reasonOk = reasonValid(reason, rule)
  const confirmDisabled =
    pending || !reasonOk || freshness !== 'fresh' || casConflict || dispatchedMaybe

  // The CAS answer REPLACES the confirm content (§4a) — three values, protection
  // framing, and no overwrite-anyway button.
  if (problem !== undefined && problem.code === 'cas-conflict') {
    return (
      <ModalShell
        title={`${name} changed while you were editing`}
        environment={environment}
        onClose={onClose}
        footer={
          <>
            <button type="button" onClick={onClose}>
              Cancel the edit
            </button>
            <button
              type="button"
              className="primary"
              onClick={() => {
                onStartOver(problem.currentValue)
              }}
            >
              Start over from the current value
            </button>
          </>
        }
      >
        <p>
          Nothing was overwritten — the engine value moved after you loaded it, so the edit
          was refused as protection.
        </p>
        <dl className="cas-values">
          <dt>The value you started from</dt>
          <dd>
            <ValuePane value={problem.expectedOldValue} />
          </dd>
          <dt>Your new value (not applied)</dt>
          <dd>
            <ValuePane value={after} />
          </dd>
          <dt>The engine's current value</dt>
          <dd>
            <ValuePane value={problem.currentValue} />
          </dd>
        </dl>
        <p className="value-muted">
          The change is attributed in the audit trail (Audit &amp; Notes tab).
        </p>
      </ModalShell>
    )
  }

  return (
    <ModalShell
      title="Verify the change"
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
            disabled={confirmDisabled}
            title={
              freshness === 'stale'
                ? 'blocked: the server value changed — reload first'
                : !reasonOk
                  ? prod
                    ? 'a reason of at least 10 characters is required on PROD'
                    : 'a reason, when given, must be at least 10 characters'
                  : undefined
            }
            onClick={() => {
              const trimmed = reason.trim()
              onDispatch(trimmed === '' ? undefined : trimmed)
            }}
          >
            {pending ? 'Applying…' : confirmLabel}
          </button>
        </>
      }
    >
      <p className="verify-sentence">{sentence}</p>

      {structured ? (
        <div className="verify-diff">
          <p className="verify-diff-summary">{diffSummary(changes, countLeaves(before))}</p>
          <ul className="verify-diff-lines">
            {changes.slice(0, 20).map((change) => (
              <li key={change.path} className={`diff-${change.kind}`}>
                <span className="diff-glyph" aria-hidden>
                  {change.kind === 'added' ? '+' : change.kind === 'removed' ? '−' : '~'}
                </span>
                <span className="diff-kind-label">{change.kind}</span> {changeLine(change)}
              </li>
            ))}
            {changes.length > 20 && (
              <li className="value-muted">… and {String(changes.length - 20)} more paths</li>
            )}
          </ul>
          <details className="raw-diff">
            <summary>raw before/after (power users)</summary>
            <div className="verify-panes">
              <ValuePane label="Current" value={before} />
              <ValuePane label="After" value={after} />
            </div>
          </details>
        </div>
      ) : (
        <div className="verify-panes">
          <ValuePane label="Current" value={before} />
          <ValuePane label="After" value={after} />
        </div>
      )}

      {/* Warning lines in fixed order (§4a): type change · PROD. */}
      {typeChanged !== null && (
        <div className="callout callout-amber" role="alert">
          Type changes from <code>{typeChanged.from}</code> to <code>{typeChanged.to}</code> —
          downstream gateways/scripts may depend on this type: text “42” and number 42 behave
          differently.
        </div>
      )}
      {prod && (
        <div className="callout callout-prod" role="alert">
          This is a PRODUCTION engine.
        </div>
      )}

      <p className="verify-meta">
        {byteDelta === 0
          ? 'no size change'
          : `size ${byteDelta > 0 ? 'grows' : 'shrinks'} by ${formatBytes(Math.abs(byteDelta))}`}
        {' · '}
        RECOVERABLE — the old value is kept in the audit trail
      </p>

      {freshness === 'checking' && <p className="zero-state">Re-checking the server value…</p>}
      {freshness === 'fresh' && (
        <p className="verify-fresh">server value re-checked — unchanged since you loaded it</p>
      )}
      {freshness === 'unknown' && (
        <div className="error-banner" role="alert">
          Could not re-check the server value ({fresh.error?.message ?? 'unreachable'}) — the
          confirm stays blocked. Close and retry when the engine answers.
        </div>
      )}
      {freshness === 'stale' && (
        <div className="error-banner" role="alert">
          Blocked: the server value changed since you loaded it.{' '}
          <button
            type="button"
            className="copy-btn"
            onClick={() => {
              onStartOver(fresh.data?.value)
            }}
          >
            start over from the current value
          </button>
        </div>
      )}

      <label className="modal-field">
        Reason {rule.required ? '(required on PROD, ≥10 chars)' : '(optional, ≥10 chars when given)'}
        <textarea
          value={reason}
          rows={2}
          maxLength={2000}
          onChange={(event) => {
            setReason(event.target.value)
          }}
        />
      </label>

      <details className="exact-request">
        <summary>exact request</summary>
        <pre className="value-body">{JSON.stringify(request, null, 2)}</pre>
        <p className="value-muted">
          POST /api/instances/{engineId}/{instanceId}/actions/edit-variable — the
          expectedOldValue is the compare-and-set precondition.
        </p>
      </details>

      {problem !== undefined && !casConflict && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}

function sameValue(a: unknown, b: unknown): boolean {
  try {
    return JSON.stringify(a ?? null) === JSON.stringify(b ?? null)
  } catch {
    return false
  }
}

function ValuePane({ label, value }: { label?: string; value: unknown }) {
  const text =
    value === null || value === undefined
      ? '(no value / null)'
      : typeof value === 'string'
        ? value === ''
          ? '(empty text)'
          : value
        : JSON.stringify(value, null, 2)
  return (
    <div className="value-pane">
      {label !== undefined && <span className="value-pane-label">{label}</span>}
      <pre className="value-body">{text}</pre>
    </div>
  )
}
