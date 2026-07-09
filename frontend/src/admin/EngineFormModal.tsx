// Add / edit an engine (docs/REGISTRY-CRUD.md §11). Secrets are entered as env-var REF NAMES only
// (never a value, iron rule). SSRF validation is server-side: a rejected base-URL comes back as the
// rule-named 400 and is shown inline. id is immutable — shown read-only on edit.
import { useState } from 'react'
import { ApiError } from '../api/client'
import { ModalShell } from '../components/ModalShell'
import type { AdminEngineDto, EngineWriteRequest } from './adminEngines'
import { toEnvironment, type Environment } from './lifecycle'

type AuthType = 'basic' | 'bearer' | 'none'

interface Props {
  /** Present = edit; absent = add. */
  existing?: AdminEngineDto
  submitting: boolean
  error: unknown
  onSubmit: (body: EngineWriteRequest) => void
  onClose: () => void
}

export function EngineFormModal({ existing, submitting, error, onSubmit, onClose }: Props) {
  const editing = existing !== undefined
  const [id, setId] = useState(existing?.id ?? '')
  const [name, setName] = useState(existing?.name ?? '')
  const [baseUrl, setBaseUrl] = useState(existing?.baseUrl ?? '')
  const [environment, setEnvironment] = useState<Environment>(toEnvironment(existing?.environment))
  const [authType, setAuthType] = useState<AuthType>(
    (existing?.authType as AuthType | undefined) ?? 'none',
  )
  const [authUsername, setAuthUsername] = useState(existing?.authUsername ?? '')
  const [passwordRef, setPasswordRef] = useState(existing?.passwordRef ?? '')
  const [tokenRef, setTokenRef] = useState(existing?.tokenRef ?? '')
  const [reason, setReason] = useState('')

  const reasonTooShort = reason.trim().length < 10
  const canSubmit =
    id.trim() !== '' && name.trim() !== '' && baseUrl.trim() !== '' && !reasonTooShort

  const submit = () => {
    if (!canSubmit) return
    onSubmit({
      id: id.trim(),
      name: name.trim(),
      baseUrl: baseUrl.trim(),
      environment,
      authType,
      authUsername: authType === 'none' ? undefined : authUsername.trim() || undefined,
      passwordRef: authType === 'basic' ? passwordRef.trim() || undefined : undefined,
      tokenRef: authType === 'bearer' ? tokenRef.trim() || undefined : undefined,
      reason: reason.trim(),
    })
  }

  const message =
    error instanceof ApiError ? error.message : error != null ? 'Request failed' : null

  return (
    <ModalShell
      title={editing ? `Edit engine ${existing.id ?? ''}` : 'Add an engine'}
      environment={environment}
      onClose={onClose}
      footer={
        <div className="modal-footer">
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="danger"
            disabled={!canSubmit || submitting}
            onClick={submit}
          >
            {submitting ? 'Saving…' : editing ? 'Save changes' : 'Add engine (as draft)'}
          </button>
        </div>
      }
    >
      <form
        className="engine-form"
        onSubmit={(e) => {
          e.preventDefault()
        }}
      >
        <label>
          Engine id {editing && <span className="muted">(immutable)</span>}
          <input
            value={id}
            readOnly={editing}
            placeholder="orders-prod"
            onChange={(e) => {
              setId(e.target.value)
            }}
          />
        </label>
        <label>
          Name
          <input
            value={name}
            onChange={(e) => {
              setName(e.target.value)
            }}
          />
        </label>
        <label>
          Base URL
          <input
            value={baseUrl}
            placeholder="https://orders.corp.example.com/flowable-rest/service"
            onChange={(e) => {
              setBaseUrl(e.target.value)
            }}
          />
        </label>
        <label>
          Environment
          <select
            value={environment}
            onChange={(e) => {
              setEnvironment(e.target.value as Environment)
            }}
          >
            <option value="DEV">dev</option>
            <option value="TEST">test</option>
            <option value="PROD">prod</option>
          </select>
        </label>
        <label>
          Auth
          <select
            value={authType}
            onChange={(e) => {
              setAuthType(e.target.value as AuthType)
            }}
          >
            <option value="none">none</option>
            <option value="basic">basic</option>
            <option value="bearer">bearer</option>
          </select>
        </label>
        {authType === 'basic' && (
          <>
            <label>
              Username
              <input
                value={authUsername}
                onChange={(e) => {
                  setAuthUsername(e.target.value)
                }}
              />
            </label>
            <label>
              Password ref <span className="muted">(env-var name, never a value)</span>
              <input
                value={passwordRef}
                placeholder="ENGINE_ORDERS_PASSWORD"
                onChange={(e) => {
                  setPasswordRef(e.target.value)
                }}
              />
            </label>
          </>
        )}
        {authType === 'bearer' && (
          <label>
            Token ref <span className="muted">(env-var name, never a value)</span>
            <input
              value={tokenRef}
              placeholder="ENGINE_ORDERS_TOKEN"
              onChange={(e) => {
                setTokenRef(e.target.value)
              }}
            />
          </label>
        )}
        <label>
          Reason <span className="muted">(≥10 chars, audited)</span>
          <input
            value={reason}
            aria-invalid={reasonTooShort}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        </label>
        <p className="strip-note">
          The BFF will dial this URL from the server network. A new engine is added disabled +
          read-only; test the connection, then enable it.
        </p>
        {message != null && (
          <p className="error-banner" role="alert">
            {message}
          </p>
        )}
      </form>
    </ModalShell>
  )
}
