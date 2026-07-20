// The runtime engine-registry admin surface (docs/REGISTRY-CRUD.md §11, R-SAFE-13). REGISTRY_ADMIN
// only — the nav link is greyed-never-hidden for others. Lists ALL rows (draft/disabled/tombstoned),
// shows secret-ref PRESENCE (never values), and drives the earned-trust lifecycle: add → probe →
// enable → disable → remove → purge. All guards are server-side; this UI mirrors them.
import { useState } from 'react'
import { RequestIdNote } from '../actions/RequestIdNote'
import { ApiError } from '../api/client'
import { useMe } from '../api/me'
import {
  useAdminEngines,
  useDrift,
  useEngineMutations,
  useEngineProposals,
  type AdminEngineDto,
  type EngineWriteOutcome,
  type EngineWriteRequest,
} from './adminEngines'
import { engineOutcomeNotice } from './adminEnginesView'
import { EngineFormModal } from './EngineFormModal'
import { rowActions, type LifecycleAction } from './lifecycle'
import { LifecycleModal } from './LifecycleModal'

const LIFECYCLE_LABEL: Record<string, string> = {
  draft: '○ Draft',
  probed: '● Probed',
  probe_failed: '▲ Probe failed',
  active: '✓ Active',
  disabled: '⏸ Disabled',
  removed: '🗑 Removed',
}

// #275: plain-language, per-class remediation text — VISIBLE row text, not tooltip-only, and
// distinguishable class-to-class so "nothing listening", "missing secret ref" and "the engine
// answered but not usefully" no longer all read as the same generic wording. Kept to safe,
// exception-TYPE-derived facts only (ProbeFailureClassifier, backend) — never the raw
// exception message, which stays audit-only (topology oracle risk, see the tooltip below).
const PROBE_FAILURE_TEXT: Record<string, string> = {
  ssrf_rejected:
    'rejected before dialing — this base URL now resolves to a disallowed or internal address',
  missing_secret_ref: 'missing credential — the configured secret env var is not set on the BFF',
  auth_rejected: 'the engine rejected the credentials (401/403)',
  unexpected_response:
    'reached the engine, but got an unexpected response — check the base URL and engine version',
  unreachable:
    'could not reach the engine — verify the base URL, port, and that it is running and network-reachable from the BFF host',
}

// Pre-#275 rows (probed once, never re-probed since) carry no failure class at all — fall back
// to the same generic wording the whole table used to show for every failure.
function probeFailureText(failureClass: string | null | undefined): string {
  const key = failureClass ?? ''
  return PROBE_FAILURE_TEXT[key] ?? PROBE_FAILURE_TEXT.unreachable
}

// #275 point 4: the OLD copy promised "the specific connection error is recorded server-side in
// this engine's audit trail" — true of the DATA (AuditService#close's response_snippet, issue
// #223/#231) but false of the UI: the Operations Log page never renders response_snippet, so
// there was no actual path for a registry admin to go read it. This corrected copy stops
// implying a UI path that doesn't exist; it still explains WHY the raw text isn't shown here.
const PROBE_FAILED_TOOLTIP =
  "The full connection error is captured server-side on this engine's probe audit entry — " +
  'not shown here (or in the Operations Log) to avoid leaking internal network details ' +
  'through the UI; retrievable via the audit API/DB by an engineer who needs the exact text.'

type FormState = { mode: 'add' } | { mode: 'edit'; engine: AdminEngineDto } | null
type LifecycleState = { action: LifecycleAction; engine: AdminEngineDto } | null

export function AdminEnginesPage() {
  const me = useMe()
  const engines = useAdminEngines()
  const drift = useDrift()
  const proposals = useEngineProposals()
  const m = useEngineMutations()
  const [form, setForm] = useState<FormState>(null)
  const [lifecycle, setLifecycle] = useState<LifecycleState>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const reportOutcome = (o: EngineWriteOutcome) => {
    setNotice(engineOutcomeNotice(o))
  }
  // #169: Approve has no modal (unlike add/edit/enable/disable/remove/purge, which each
  // surface their own mutation's error via a modal `error` prop) — without this, a real,
  // specific server refusal (e.g. "the proposer cannot approve their own proposal") landed in
  // React Query state and rendered nowhere; the only signal was the proposal silently staying
  // pending. Registry CRUD is deliberately NOT behind the dangerous-set reauth gate
  // (AdminEnginesController's own doc comment), so unlike AdminAccessPage's writeError this
  // needs no 401/ReauthNotice branch.
  const approveError = m.approve.error

  // Greyed-never-hidden (R-UXQ): the nav renders for everyone; the page itself states the gate.
  // Fails CLOSED while `me` is unresolved (isPending) — issue #208: a `data !== undefined`-only
  // check let this fall through to the full privileged table for one render on every fresh
  // mount (e.g. right after an in-session identity switch, when the query cache was just
  // cleared and `me.data` is briefly `undefined` again), the one gate in the app that showed
  // privileged content by default instead of restricting it. Every other full-page gate
  // (Shell.tsx's nav links) already keys off a positive `=== true` match for the same reason.
  if (me.isPending) {
    return (
      <div className="page">
        <header className="page-header">
          <h2>Engine registry</h2>
        </header>
        <p className="muted">Resolving your access…</p>
      </div>
    )
  }
  if (me.data?.registryAdmin !== true) {
    // R-AUD-04 (#272): the gate is decided client-side off the /api/me hint, but the list query
    // still fired and was refused server-side — carry ITS quotable request id into the block
    // alert so a user reading only the page can hand support a correlation id.
    const blockedId = engines.error instanceof ApiError ? engines.error.requestId : undefined
    return (
      <div className="page">
        <header className="page-header">
          <h2>Engine registry</h2>
        </header>
        <p className="error-banner" role="alert">
          This surface requires the <strong>REGISTRY_ADMIN</strong> grant. Ask an administrator to
          add you to the registry-admin group.
        </p>
        <RequestIdNote requestId={blockedId} />
      </div>
    )
  }

  // DriftReport serializes isEmpty() as `empty`; arrays are optional in the contract.
  const driftEmpty = drift.data?.empty ?? true

  return (
    <div className="page">
      <header className="page-header">
        <h2>Engine registry</h2>
        <p className="muted">
          Onboard, tune, and retire Flowable engines at runtime. The BFF dials each base-URL from
          the server network — every change is SSRF-validated and audited.
        </p>
        <button
          type="button"
          className="primary"
          onClick={() => {
            setForm({ mode: 'add' })
          }}
        >
          Add engine
        </button>
      </header>

      {notice !== null && (
        <div className="banner banner-info" role="status">
          {notice}
        </div>
      )}
      {approveError instanceof ApiError && (
        <div className="banner banner-warn" role="alert">
          {approveError.message}
        </div>
      )}

      {!driftEmpty && (
        <p className="strip-note" role="status">
          YAML drift ignored (DB is authoritative): added {String(drift.data?.added?.length ?? 0)},
          removed {String(drift.data?.removed?.length ?? 0)}, changed{' '}
          {String(drift.data?.changed?.length ?? 0)}.
        </p>
      )}

      {engines.isPending && <p className="muted">Loading engines…</p>}
      {engines.isError && (
        <p className="error-banner" role="alert">
          {engines.error instanceof ApiError && engines.error.status === 403
            ? 'You are not a registry administrator.'
            : 'The registry could not be loaded.'}
        </p>
      )}

      {engines.isSuccess && engines.data.length === 0 && (
        <div className="zero-state">
          <p>No engines are registered yet.</p>
          <button
            type="button"
            className="primary"
            onClick={() => {
              setForm({ mode: 'add' })
            }}
          >
            Add your first engine
          </button>
        </div>
      )}

      {engines.isSuccess && engines.data.length > 0 && (
        <table className="engines-table">
          <thead>
            <tr>
              <th>Engine</th>
              <th>Env</th>
              <th>Lifecycle</th>
              <th>Mode</th>
              <th>Secret</th>
              <th>Base URL</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {engines.data.map((e) => (
              <EngineRow
                key={e.id}
                engine={e}
                probing={m.probe.isPending && m.probe.variables === e.id}
                onEdit={() => {
                  setForm({ mode: 'edit', engine: e })
                }}
                onProbe={() => {
                  m.probe.mutate(e.id ?? '')
                }}
                onLifecycle={(action) => {
                  setLifecycle({ action, engine: e })
                }}
              />
            ))}
          </tbody>
        </table>
      )}

      {form?.mode === 'add' && (
        <EngineFormModal
          submitting={m.add.isPending}
          error={m.add.error}
          onSubmit={(body: EngineWriteRequest) => {
            m.add.mutate(body, {
              onSuccess: () => {
                setForm(null)
              },
            })
          }}
          onClose={() => {
            m.add.reset()
            setForm(null)
          }}
        />
      )}
      {form?.mode === 'edit' && (
        <EngineFormModal
          existing={form.engine}
          submitting={m.edit.isPending}
          error={m.edit.error}
          onSubmit={(body: EngineWriteRequest) => {
            m.edit.mutate(
              { id: form.engine.id ?? '', body },
              {
                onSuccess: () => {
                  setForm(null)
                },
              },
            )
          }}
          onClose={() => {
            m.edit.reset()
            setForm(null)
          }}
        />
      )}

      {lifecycle !== null && (
        <LifecycleModal
          action={lifecycle.action}
          engine={lifecycle.engine}
          submitting={mutationFor(m, lifecycle.action).isPending}
          error={mutationFor(m, lifecycle.action).error}
          onConfirm={(vars) => {
            const id = lifecycle.engine.id ?? ''
            const closeOnly = {
              onSuccess: () => {
                setLifecycle(null)
              },
            }
            // enable/remove/purge are the dangerous set (R-SAFE-08, #91) — they may come back
            // `proposed` rather than `applied`; report the outcome before closing the modal.
            const closeAndReport = {
              onSuccess: (o: EngineWriteOutcome) => {
                reportOutcome(o)
                setLifecycle(null)
              },
            }
            if (lifecycle.action === 'enable')
              m.enable.mutate(
                {
                  id,
                  readWrite: vars.readWrite,
                  confirmToken: vars.confirmToken,
                  reason: vars.reason,
                },
                closeAndReport,
              )
            else if (lifecycle.action === 'disable')
              m.disable.mutate({ id, reason: vars.reason }, closeOnly)
            else if (lifecycle.action === 'remove')
              m.remove.mutate(
                { id, confirmToken: vars.confirmToken, reason: vars.reason },
                closeAndReport,
              )
            else
              m.purge.mutate(
                { id, confirmToken: vars.confirmToken, reason: vars.reason },
                closeAndReport,
              )
          }}
          onClose={() => {
            mutationFor(m, lifecycle.action).reset()
            setLifecycle(null)
          }}
        />
      )}

      <section aria-labelledby="engine-proposals-h">
        <h3 id="engine-proposals-h">Pending proposals (four-eyes)</h3>
        {(proposals.data ?? []).length === 0 ? (
          <p className="muted">No pending proposals.</p>
        ) : (
          <ul className="proposal-inbox">
            {(proposals.data ?? []).map((p) => (
              <li key={p.id}>
                <span className="proposal-summary">{p.summary}</span>
                <span className="muted">
                  {' '}
                  — proposed by {p.proposer}; reason: {p.reason}
                </span>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    if (p.id !== undefined) m.approve.mutate(p.id, { onSuccess: reportOutcome })
                  }}
                >
                  Approve
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="muted">
          Only a REGISTRY_ADMIN who is not the proposer and not in the proposer&apos;s
          REGISTRY_ADMIN group may approve.
        </p>
      </section>
    </div>
  )
}

function mutationFor(m: ReturnType<typeof useEngineMutations>, action: LifecycleAction) {
  return action === 'enable'
    ? m.enable
    : action === 'disable'
      ? m.disable
      : action === 'remove'
        ? m.remove
        : m.purge
}

interface RowProps {
  engine: AdminEngineDto
  probing: boolean
  onEdit: () => void
  onProbe: () => void
  onLifecycle: (action: LifecycleAction) => void
}

// #275: the Lifecycle cell for one row — visible-text failure detail for probe_failed (point 1/2),
// and an explicit positive reachability badge for active engines (point 3), distinct from the
// mere "no error seen yet" reading of a bare lifecycle label.
function lifecycleCell(engine: AdminEngineDto, lifecycle: string) {
  if (lifecycle === 'probe_failed') {
    // Issue #223/#231 established the doctrine this still follows: the raw exception TEXT stays
    // audit-only (topology oracle risk — differing text would let an admin fingerprint what's
    // reachable on the BFF's internal network). #275 adds a coarse, UI-safe failure CLASS
    // (ProbeFailureClassifier, backend) that IS safe to show as visible text, distinguishing
    // "nothing listening" from "missing secret ref" from "engine answered, but not usefully".
    return (
      <span className="lifecycle-negative" title={PROBE_FAILED_TOOLTIP}>
        {LIFECYCLE_LABEL[lifecycle]} — {probeFailureText(engine.lastProbeFailureClass)}
      </span>
    )
  }
  if (lifecycle === 'active') {
    return <ActiveHealthBadge reachableNow={engine.reachableNow} />
  }
  return <>{LIFECYCLE_LABEL[lifecycle] ?? lifecycle}</>
}

/**
 * #275 point 3: "active" (enabled) is a POLICY state, not a live health signal — the tester
 * finding was that an active row's checkmark reads as "not failing" (mere absence of an error),
 * indistinguishable from "confirmed healthy just now". This joins in the SAME live
 * EngineHealthService result the header strip already shows (`EngineRegistry#healthOf`, 30s
 * cycle) so the badge is an honest, freshness-checked claim, never a fabricated one:
 * `reachableNow` is `null`/`undefined` until that engine has been probed at least once since
 * becoming active (never confused with a confirmed true/false).
 */
function ActiveHealthBadge({ reachableNow }: { reachableNow: boolean | null | undefined }) {
  if (reachableNow === true) {
    return <span className="lifecycle-positive">{LIFECYCLE_LABEL.active} — reachable</span>
  }
  if (reachableNow === false) {
    return <span className="lifecycle-negative">{LIFECYCLE_LABEL.active} — ⚠ unreachable now</span>
  }
  return <span className="muted">{LIFECYCLE_LABEL.active} — health pending</span>
}

function EngineRow({ engine, probing, onEdit, onProbe, onLifecycle }: RowProps) {
  const env = engine.environment ?? 'unknown'
  const lifecycle = engine.lifecycle ?? 'draft'
  const ref = engine.passwordRef ?? engine.tokenRef
  const present = engine.passwordRefPresent || engine.tokenRefPresent
  const actions = rowActions(lifecycle)
  return (
    <tr className={`env-band-${env}`}>
      <td>
        <strong>{engine.id}</strong>
        <div className="muted">{engine.name}</div>
      </td>
      <td>
        <span className={`env-badge env-${env}`}>{env.toUpperCase()}</span>
      </td>
      <td>{lifecycleCell(engine, lifecycle)}</td>
      <td>{engine.mode}</td>
      <td>
        {ref == null ? (
          <span className="muted">—</span>
        ) : (
          <span title={ref}>
            {ref}: {present ? '✓ present' : '✗ absent'}
          </span>
        )}
      </td>
      <td className="mono">{engine.baseUrl}</td>
      <td className="row-actions">
        {actions.probe && (
          <button type="button" disabled={probing} onClick={onProbe}>
            {probing ? 'Testing…' : 'Test connection'}
          </button>
        )}
        {actions.edit && (
          <button type="button" onClick={onEdit}>
            Edit
          </button>
        )}
        {actions.enable && (
          <button
            type="button"
            onClick={() => {
              onLifecycle('enable')
            }}
          >
            Enable
          </button>
        )}
        {actions.disable && (
          <button
            type="button"
            onClick={() => {
              onLifecycle('disable')
            }}
          >
            Disable
          </button>
        )}
        {actions.remove && (
          <button
            type="button"
            className="danger"
            onClick={() => {
              onLifecycle('remove')
            }}
          >
            Remove
          </button>
        )}
        {actions.purge && (
          <button
            type="button"
            className="danger"
            onClick={() => {
              onLifecycle('purge')
            }}
          >
            Purge
          </button>
        )}
      </td>
    </tr>
  )
}
