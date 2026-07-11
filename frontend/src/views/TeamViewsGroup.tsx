import { useState } from 'react'
import { Link } from 'react-router'
import type { TeamViewDto } from '../api/model'
import { useMe } from '../api/me'
import { ActionHint } from '../components/ActionHint'
import { ModalShell } from '../components/ModalShell'
import { useTeamViews } from './useTeamViews'
import { isDangling, teamViewTitle } from './teamViewModel'

/**
 * The "Team views" group in the Stage-0 picker (SHARED-VIEWS.md §4.6): the curated canon the caller
 * may see, between the system windows and their private views (precedence System → Team → Private).
 * Each chip wears a non-color "TEAM" tag (R-UXQ-01: a bordered label, never hue-only) with author +
 * scope in the tooltip. A DANGLING canon (its scoped engine gone) is greyed and NON-clickable — never
 * a live link to a clean-looking "no failures" (§4.5).
 *
 * <p>The inline "✕" is the AUTHOR's self-service unpublish. Unpublish is a MODERATION verb
 * (usability W2 #3, R-SAFE-16): it yanks a shared entry point from the whole team, so a reason ≥10
 * is REQUIRED for every caller — author included — collected in a small confirm dialog and rendered
 * in the operations log. The ✕ shows only on your own canon (author === you); moderating ANOTHER's
 * canon is a separate affordance (follow-up), not a silent no-op here. A real failure (audit down /
 * network) is surfaced, never swallowed.
 */
export function TeamViewsGroup() {
  const { views, unpublish } = useTeamViews()
  const { data: me } = useMe()
  const [confirming, setConfirming] = useState<TeamViewDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  if (views.length === 0) return null

  return (
    <div className="view-chips team-views" role="group" aria-label="Team views">
      {views.map((view) => {
        const dangling = isDangling(view)
        const isAuthor = me?.username !== undefined && me.username === view.author
        return (
          <span
            key={view.id}
            className={dangling ? 'view-chip team-view dangling' : 'view-chip team-view'}
            title={teamViewTitle(view)}
          >
            <span className="team-tag" aria-label="team view">
              TEAM
            </span>
            {dangling ? (
              <span className="team-view-name" aria-disabled="true">
                {view.name} <span className="team-view-note">(scope unavailable)</span>
              </span>
            ) : (
              <Link to={`/search?${view.search ?? ''}`}>{view.name}</Link>
            )}
            {isAuthor && (
              <button
                type="button"
                aria-label={`unpublish team view ${view.name ?? ''}`}
                title="remove your view from team views (asks for a reason — it is recorded in the operations log)"
                onClick={() => {
                  setError(null)
                  setConfirming(view)
                }}
              >
                ✕
              </button>
            )}
          </span>
        )
      })}
      {confirming !== null && (
        <UnpublishModal
          view={confirming}
          onConfirm={(reason) => {
            const id = confirming.id
            const name = confirming.name ?? ''
            setConfirming(null)
            if (id !== undefined) {
              void unpublish(id, reason).catch((cause: unknown) => {
                setError(
                  `Couldn’t remove “${name}” — ${
                    cause instanceof Error ? cause.message : 'the audit store may be down.'
                  } Nothing changed.`,
                )
              })
            }
          }}
          onClose={() => {
            setConfirming(null)
          }}
        />
      )}
      {error !== null && (
        <p className="team-views-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

/** The unpublish reason dialog — same ≥10 gate + inline copy as every other submit door. */
function UnpublishModal({
  view,
  onConfirm,
  onClose,
}: {
  view: TeamViewDto
  onConfirm: (reason: string) => void
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const reasonOk = reason.trim().length >= 10
  const reasonGate = !reasonOk ? 'Reason too short — 10+ characters' : undefined
  return (
    <ModalShell
      title={`Unpublish “${view.name ?? ''}” from team views?`}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <div className="action-slot">
            <button
              type="button"
              className="primary"
              disabled={!reasonOk}
              aria-describedby={reasonGate !== undefined ? 'unpublish-reason-hint' : undefined}
              onClick={() => {
                onConfirm(reason.trim())
              }}
            >
              Unpublish {view.name ?? ''}
            </button>
            {reasonGate !== undefined && (
              <ActionHint id="unpublish-reason-hint" text={reasonGate} tone="gate" />
            )}
          </div>
        </>
      }
    >
      <p>
        Removes it from every teammate&rsquo;s picker. Your private bookmark is untouched — publish
        was a snapshot copy, so re-publishing restores it. Reversible.
      </p>
      <label className="modal-field">
        Why are you removing it? (required, 10+ characters — recorded in the operations log)
        <textarea
          value={reason}
          rows={2}
          maxLength={500}
          aria-invalid={!reasonOk}
          onChange={(event) => {
            setReason(event.target.value)
          }}
        />
      </label>
    </ModalShell>
  )
}
