// Enable / disable / remove / purge confirm (docs/REGISTRY-CRUD.md §6/§7). Reason is always
// audited (≥10). The dangerous flips carry a TYPED TOKEN (the engine id): prod enable-read-write,
// remove, and purge. Enter never submits (ModalShell) — confirms are explicit clicks.
import { useState } from 'react'
import { ApiError } from '../api/client'
import { ModalShell } from '../components/ModalShell'
import type { AdminEngineDto } from './adminEngines'
import { needsTypedToken, type LifecycleAction } from './lifecycle'

interface Props {
  action: LifecycleAction
  engine: AdminEngineDto
  submitting: boolean
  error: unknown
  onConfirm: (vars: { readWrite: boolean; confirmToken: string; reason: string }) => void
  onClose: () => void
}

const TITLES: Record<LifecycleAction, string> = {
  enable: 'Enable engine',
  disable: 'Disable engine',
  remove: 'Remove engine',
  purge: 'Purge engine (permanent)',
}

export function LifecycleModal({ action, engine, submitting, error, onConfirm, onClose }: Props) {
  const [readWrite, setReadWrite] = useState(false)
  const [confirmToken, setConfirmToken] = useState('')
  const [reason, setReason] = useState('')

  const id = engine.id ?? ''
  const isProd = engine.environment === 'prod'
  const needsToken = needsTypedToken(action, engine.environment ?? undefined, readWrite)
  const reasonOk = reason.trim().length >= 10
  const tokenOk = !needsToken || confirmToken === id
  const canConfirm = reasonOk && tokenOk

  const message =
    error instanceof ApiError ? error.message : error != null ? 'Request failed' : null

  return (
    <ModalShell
      title={`${TITLES[action]} · ${id}`}
      environment={engine.environment ?? undefined}
      onClose={onClose}
      footer={
        <div className="modal-footer">
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="danger"
            disabled={!canConfirm || submitting}
            onClick={() => {
              if (canConfirm) onConfirm({ readWrite, confirmToken, reason: reason.trim() })
            }}
          >
            {submitting ? 'Working…' : TITLES[action]}
          </button>
        </div>
      }
    >
      <div className="lifecycle-confirm">
        {action === 'enable' && (
          <label className="checkbox-row">
            <input
              type="checkbox"
              checked={readWrite}
              onChange={(e) => {
                setReadWrite(e.target.checked)
              }}
            />
            Enable in <strong>read-write</strong> mode (mutating verbs allowed)
          </label>
        )}
        {action === 'enable' && readWrite && isProd && (
          <p className="strip-note">
            Read-write on a <strong>prod</strong> engine: the BFF may mutate live production state.
          </p>
        )}
        {action === 'purge' && (
          <p className="error-banner">
            Purge hard-deletes the tombstone — id→name history for past audit/notes is lost. This
            cannot be undone.
          </p>
        )}

        {needsToken && (
          <label>
            Type the engine id <code>{id}</code> to confirm
            <input
              value={confirmToken}
              aria-invalid={!tokenOk}
              placeholder={id}
              onChange={(e) => {
                setConfirmToken(e.target.value)
              }}
            />
          </label>
        )}
        <label>
          Reason <span className="muted">(≥10 chars, audited)</span>
          <input
            value={reason}
            aria-invalid={!reasonOk}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        </label>
        {message != null && (
          <p className="error-banner" role="alert">
            {message}
          </p>
        )}
      </div>
    </ModalShell>
  )
}
