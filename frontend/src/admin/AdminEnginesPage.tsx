// The runtime engine-registry admin surface (docs/REGISTRY-CRUD.md §11, R-SAFE-13). REGISTRY_ADMIN
// only — the nav link is greyed-never-hidden for others. Lists ALL rows (draft/disabled/tombstoned),
// shows secret-ref PRESENCE (never values), and drives the earned-trust lifecycle: add → probe →
// enable → disable → remove → purge. All guards are server-side; this UI mirrors them.
import { useState } from 'react'
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
  if (me.data !== undefined && me.data.registryAdmin !== true) {
    return (
      <div className="page">
        <header className="page-header">
          <h2>Engine registry</h2>
        </header>
        <p className="error-banner" role="alert">
          This surface requires the <strong>REGISTRY_ADMIN</strong> grant. Ask an administrator to
          add you to the registry-admin group.
        </p>
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
      <td>{LIFECYCLE_LABEL[lifecycle] ?? lifecycle}</td>
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
