import { useState } from 'react'
import { ReauthNotice, useReauthStale } from '../actions/ReauthNotice'
import { ApiError } from '../api/client'
import { isReauthBody } from '../auth/reauth'
import {
  downloadAccessReview,
  useAccessMapping,
  useAccessMutations,
  useProposals,
  type FleetView,
  type GrantRequest,
  type LadderView,
  type Outcome,
} from './accessAdmin'
import { distinctGroups, outcomeNotice } from './accessView'
import { FleetChip } from './FleetChip'

/**
 * `/admin/access` (IDP-SECURITY.md §12) — the apex group→scope mapping surface, ACCESS_ADMIN-only.
 * Shows the effective mapping (ladder + intrinsic fleet chips), an add/remove-grant form whose
 * widening writes surface the four-eyes proposal + the concrete eligible-approver next-move, the
 * pending-proposal inbox (a second independent ACCESS_ADMIN approves), and the access-review export.
 * Enter never submits (dangerous surface); every action is an explicit button.
 */
export function AdminAccessPage() {
  const mapping = useAccessMapping()
  const proposals = useProposals()
  const { add, remove, approve } = useAccessMutations()
  const [notice, setNotice] = useState<string | null>(null)
  // Dangerous-set freshness (IDP-SECURITY.md §5): every mapping WRITE (add/remove/approve) is
  // re-auth-gated server-side — pre-empt via the /api/me hint, or react to the 401 challenge.
  const writeError = add.error ?? remove.error ?? approve.error
  const reauthNeeded =
    useReauthStale() ||
    (writeError instanceof ApiError && writeError.status === 401 && isReauthBody(writeError.body))

  if (mapping.error instanceof ApiError && mapping.error.status === 403) {
    return (
      <main className="page">
        <h2>Access administration</h2>
        <p className="muted">
          Requires the <strong>ACCESS_ADMIN</strong> grant — the apex authority over the group→scope
          mapping. You are signed in without it.
        </p>
      </main>
    )
  }

  const ladder = mapping.data?.ladderGrants ?? []
  const fleet = mapping.data?.fleetGrants ?? []
  const groups = distinctGroups(ladder, fleet)

  const reportOutcome = (o: Outcome) => {
    setNotice(outcomeNotice(o))
  }

  return (
    <main className="page">
      <h2>Access administration</h2>
      <p className="muted">
        The group→scope mapping — the single most privileged store in the tool. A row here can grant
        engine authority, repoint the credential vault (REGISTRY_ADMIN), or mint the apex
        (ACCESS_ADMIN). Widening changes go through four-eyes.
      </p>

      {notice !== null && (
        <div className="banner banner-info" role="status">
          {notice}
        </div>
      )}
      {reauthNeeded ? (
        <ReauthNotice />
      ) : (
        writeError instanceof ApiError && (
          <div className="banner banner-warn" role="alert">
            {writeError.message}
          </div>
        )
      )}

      <section aria-labelledby="mapping-h">
        <h3 id="mapping-h">Effective mapping</h3>
        {mapping.isLoading && <p className="muted">Loading…</p>}
        <table className="grants-table">
          <thead>
            <tr>
              <th scope="col">Group</th>
              <th scope="col">Grant type</th>
              <th scope="col">Grant</th>
              <th scope="col">Scope</th>
              <th scope="col">Source</th>
              <th scope="col">Action</th>
            </tr>
          </thead>
          <tbody>
            {groups.map((group) => (
              <GroupRows
                key={group}
                group={group}
                ladder={ladder.filter((r) => r.group === group)}
                fleet={fleet.filter((r) => r.group === group)}
                onRemove={(body) => {
                  remove.mutate(body, { onSuccess: reportOutcome })
                }}
              />
            ))}
          </tbody>
        </table>
      </section>

      <AddGrantForm
        onAdd={(body) => {
          add.mutate(body, { onSuccess: reportOutcome })
        }}
      />

      <section aria-labelledby="proposals-h">
        <h3 id="proposals-h">Pending proposals (four-eyes)</h3>
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
                    if (p.id !== undefined) approve.mutate(p.id, { onSuccess: reportOutcome })
                  }}
                >
                  Approve
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="muted">
          Only an ACCESS_ADMIN who is not the proposer and not in the affected group may approve.
        </p>
      </section>

      <section aria-labelledby="review-h">
        <h3 id="review-h">Access review — who can do what</h3>
        <p className="muted">The full effective-grant export (the release-gate artifact).</p>
        <button type="button" className="btn" onClick={() => void downloadAccessReview('csv')}>
          Download CSV
        </button>
        <button type="button" className="btn" onClick={() => void downloadAccessReview('md')}>
          Download Markdown
        </button>
      </section>
    </main>
  )
}

function GroupRows({
  group,
  ladder,
  fleet,
  onRemove,
}: {
  group: string
  ladder: LadderView[]
  fleet: FleetView[]
  onRemove: (body: GrantRequest) => void
}) {
  return (
    <>
      {ladder.map((r, i) => (
        <tr key={`l-${group}-${String(i)}`}>
          <td>{group}</td>
          <td>ladder</td>
          <td>
            <span className="ladder-chip">{r.role}</span>
          </td>
          <td>
            {r.engineId}/{r.tenantId}
          </td>
          <td className="muted">{r.source}</td>
          <td>
            <button
              type="button"
              className="btn"
              onClick={() => {
                onRemove({
                  type: 'ladder',
                  group,
                  role: r.role,
                  engineId: r.engineId,
                  tenantId: r.tenantId,
                  reason: `revoke ${r.role ?? ''} on ${r.engineId ?? ''} from ${group}`,
                })
              }}
            >
              Revoke
            </button>
          </td>
        </tr>
      ))}
      {fleet.map((r, i) => (
        <tr key={`f-${group}-${String(i)}`}>
          <td>{group}</td>
          <td>fleet</td>
          <td>
            <FleetChip kind={r.grant ?? ''} />
          </td>
          <td className="muted">— (fleet-wide)</td>
          <td className="muted">{r.source}</td>
          <td>
            <button
              type="button"
              className="btn"
              onClick={() => {
                onRemove({
                  type: 'fleet',
                  group,
                  fleetGrant: r.grant,
                  reason: `revoke fleet ${r.grant ?? ''} from ${group} (four-eyes)`,
                })
              }}
            >
              Revoke
            </button>
          </td>
        </tr>
      ))}
    </>
  )
}

function AddGrantForm({ onAdd }: { onAdd: (body: GrantRequest) => void }) {
  const [type, setType] = useState<'ladder' | 'fleet'>('ladder')
  const [group, setGroup] = useState('')
  const [role, setRole] = useState('VIEWER')
  const [engineId, setEngineId] = useState('*')
  const [tenantId, setTenantId] = useState('*')
  const [fleetGrant, setFleetGrant] = useState('REGISTRY_ADMIN')
  const [reason, setReason] = useState('')

  const submit = () => {
    onAdd(
      type === 'fleet'
        ? { type, group, fleetGrant, reason }
        : { type, group, role, engineId, tenantId, reason },
    )
  }

  return (
    <section aria-labelledby="add-h">
      <h3 id="add-h">Add a grant</h3>
      {/* Enter never submits this dangerous form — onSubmit is prevented; the button is explicit. */}
      <form
        className="grant-form"
        onSubmit={(e) => {
          e.preventDefault()
        }}
      >
        <label>
          Type
          <select
            value={type}
            onChange={(e) => {
              setType(e.target.value as 'ladder' | 'fleet')
            }}
          >
            <option value="ladder">ladder</option>
            <option value="fleet">fleet</option>
          </select>
        </label>
        <label>
          Group
          <input
            value={group}
            onChange={(e) => {
              setGroup(e.target.value)
            }}
            placeholder="idp-group"
          />
        </label>
        {type === 'ladder' ? (
          <>
            <label>
              Role
              <select
                value={role}
                onChange={(e) => {
                  setRole(e.target.value)
                }}
              >
                {['VIEWER', 'RESPONDER', 'OPERATOR', 'ADMIN'].map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Engine
              <input
                value={engineId}
                onChange={(e) => {
                  setEngineId(e.target.value)
                }}
              />
            </label>
            <label>
              Tenant
              <input
                value={tenantId}
                onChange={(e) => {
                  setTenantId(e.target.value)
                }}
              />
            </label>
          </>
        ) : (
          <label>
            Fleet grant
            <select
              value={fleetGrant}
              onChange={(e) => {
                setFleetGrant(e.target.value)
              }}
            >
              <option value="REGISTRY_ADMIN">REGISTRY_ADMIN</option>
              <option value="ACCESS_ADMIN">ACCESS_ADMIN</option>
            </select>
          </label>
        )}
        <label className="reason">
          Reason (≥10 chars)
          <input
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        </label>
        <button
          type="button"
          className="primary"
          disabled={group.trim() === '' || reason.trim().length < 10}
          onClick={submit}
        >
          Add grant
        </button>
      </form>
      <p className="muted">
        A self-widen, any ≥OPERATOR grant with a wildcard engine/tenant, and any fleet grant
        create/remove require a second ACCESS_ADMIN to approve.
      </p>
    </section>
  )
}
