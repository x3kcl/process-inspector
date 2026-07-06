import { useState } from 'react'
import type { FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { clearBasicAuth, setBasicAuth } from '../api/auth'
import { ApiError } from '../api/client'
import { fetchEngines } from '../api/queries'

/**
 * Dev-chain sign-in (SecurityConfig !oidc profile): credentials are validated with one
 * authenticated call, then every query re-runs. Shown whenever any query answers 401.
 */
export function SignIn() {
  const queryClient = useQueryClient()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setMessage(null)
    setBasicAuth(username, password)
    void fetchEngines()
      .then(async () => {
        await queryClient.invalidateQueries()
      })
      .catch((error: unknown) => {
        clearBasicAuth()
        setMessage(
          error instanceof ApiError && error.status === 401
            ? 'Invalid credentials'
            : error instanceof Error
              ? error.message
              : 'Sign-in failed',
        )
      })
      .finally(() => {
        setBusy(false)
      })
  }

  return (
    <div className="signin-backdrop">
      <form className="signin" onSubmit={submit}>
        <h2>Sign in</h2>
        <label>
          Username
          <input
            value={username}
            onChange={(e) => {
              setUsername(e.target.value)
            }}
            autoComplete="username"
            autoFocus
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => {
              setPassword(e.target.value)
            }}
            autoComplete="current-password"
          />
        </label>
        {message !== null && (
          <p className="signin-error" role="alert">
            {message}
          </p>
        )}
        <button type="submit" className="primary" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
