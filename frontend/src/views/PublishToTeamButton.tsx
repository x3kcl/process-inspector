import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useMe } from '../api/me'
import { ActionHint } from '../components/ActionHint'
import { useTeamViews } from './useTeamViews'

/**
 * "Publish to team…" (SHARED-VIEWS.md §4.6): the DELIBERATE second act that turns one operator's
 * private bookmark into team canon — kept OFF the hot save path, living where the saved view already
 * is (the Stage-0 section). Greyed-never-hidden (SPEC §6): a VIEWER/RESPONDER sees it disabled with
 * why, because publishing needs OPERATOR+ (the BFF is the real gate — this only greys). The scope is
 * DERIVED server-side from the search; the form offers only the optional runbook affordances that
 * turn a bookmark into canon (R-BAU-03 model). The server's 403/400/409 is surfaced inline, honestly.
 */
export function PublishToTeamButton({ name, search }: { name: string; search: string }) {
  const { data: me } = useMe()
  const { publish } = useTeamViews()
  const [open, setOpen] = useState(false)
  const [description, setDescription] = useState('')
  const [runbookUrl, setRunbookUrl] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const canPublish = me?.role === 'OPERATOR' || me?.role === 'ADMIN'

  if (!open) {
    return (
      <span className="action-slot">
        <button
          type="button"
          className="publish-team"
          disabled={!canPublish}
          aria-describedby={canPublish ? undefined : 'publish-team-hint'}
          title={
            canPublish
              ? 'Publish this view as team canon everyone with access will see'
              : 'Publishing team views needs the OPERATOR role'
          }
          onClick={() => {
            setOpen(true)
          }}
        >
          Publish to team…
        </button>
        {/* W2 #6 (T7): the gate is visible and names the missing grant — the same
            ActionHint pattern as every other disabled action control. */}
        {!canPublish && me !== undefined && (
          <ActionHint
            id="publish-team-hint"
            text={`Requires OPERATOR — you are ${me.role ?? 'unknown'}`}
            tone="gate"
          />
        )}
      </span>
    )
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(null)
    void publish({
      name,
      search,
      description: description.trim() === '' ? undefined : description.trim(),
      runbookUrl: runbookUrl.trim() === '' ? undefined : runbookUrl.trim(),
    })
      .then(() => {
        setOpen(false)
        setDescription('')
        setRunbookUrl('')
      })
      .catch((e: unknown) => {
        setError(publishError(e))
      })
      .finally(() => {
        setBusy(false)
      })
  }

  return (
    <form className="publish-team-form" onSubmit={submit} aria-label={`publish ${name} to team`}>
      <p className="publish-team-scope">
        Visible to everyone with access to the engines this view targets. The scope is set from the
        view&apos;s own filters.
      </p>
      <input
        aria-label="runbook description"
        placeholder="What is this for? (optional, ≤500 chars)"
        maxLength={500}
        value={description}
        onChange={(event) => {
          setDescription(event.target.value)
        }}
      />
      <input
        aria-label="runbook URL"
        placeholder="Runbook URL (optional, https://…)"
        value={runbookUrl}
        onChange={(event) => {
          setRunbookUrl(event.target.value)
        }}
      />
      {error !== null && (
        <p className="publish-team-error" role="alert">
          {error}
        </p>
      )}
      <div className="publish-team-actions">
        <button type="submit" disabled={busy}>
          {busy ? 'Publishing…' : 'Publish'}
        </button>
        <button
          type="button"
          onClick={() => {
            setOpen(false)
            setError(null)
          }}
        >
          Cancel
        </button>
      </div>
    </form>
  )
}

/** Map the BFF's governed refusal to [what]+[why] copy (R-UXQ-05) — never a bare status. */
export function publishError(e: unknown): string {
  if (e instanceof ApiError) {
    switch (e.status) {
      case 403:
        return 'You don’t have permission to publish this scope — publishing a wildcard scope needs ADMIN.'
      case 409:
        return 'Team canon with this name already exists in this scope. Pick another name or ask a scope admin.'
      case 400:
        return 'This view can’t be published as-is (its search may reach engines outside its scope, or a field is invalid).'
      case 503:
        return 'Publishing is temporarily unavailable (the audit store is down). Nothing was published.'
      default:
        return 'Couldn’t publish this view to the team. Please try again.'
    }
  }
  return 'Couldn’t publish this view to the team. Please try again.'
}
