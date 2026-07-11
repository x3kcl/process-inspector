import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import type { SiblingInstanceRef } from '../../api/model'
import { formatSeconds } from '../../lib/format'
import { Ts } from '../../lib/Ts'
import type { TabProps } from '../InspectPage'
import { useNearestSibling, useSiblingDiff } from '../useInstanceQueries'
import { divergenceMarkers } from './diffFormat'
import { selectComparePane } from './siblingState'
import { TimingBars } from './TimingBars'
import { VariableDiffPane } from './VariableDiffPane'

/**
 * Sibling diff (SPEC §5.2), a dedicated comparison surface: "why did THIS one fail when the
 * last one succeeded?". The nearest completed sibling auto-populates the comparison; a manual
 * process-instance id overrides it. The chosen sibling lives in ?sibling= so a comparison is
 * a shareable, ticket-pasteable deep link like the rest of Stage 2.
 *
 * <p>All three panes read the BFF's historic-only diff — nothing here touches runtime, and
 * variables are compared on the byte-capped projection (over-cap pairs are flagged, never
 * fetched in full).
 */
export default function ComparisonTab({ engineId, instanceId, onDivergence }: TabProps) {
  const [params, setParams] = useSearchParams()
  const siblingParam = params.get('sibling') ?? undefined

  const nearest = useNearestSibling(engineId, instanceId)
  const suggested =
    nearest.data?.found === true ? nearest.data.sibling?.processInstanceId : undefined
  const effectiveSibling = siblingParam ?? suggested
  const usingSuggested = siblingParam === undefined && effectiveSibling !== undefined
  const diff = useSiblingDiff(engineId, instanceId, effectiveSibling)

  // Option B (usability loop 2026-07-07): drive the ▲/△ overlay on the ONE always-on top
  // diagram instead of mounting a second canvas here. Report the path up while this tab is
  // mounted; the cleanup clears it so leaving Compare reverts the diagram to plain markers.
  const path = diff.data?.path
  useEffect(() => {
    onDivergence?.(path === undefined ? undefined : divergenceMarkers(path))
    return () => onDivergence?.(undefined)
  }, [path, onDivergence])

  const applySibling = (id: string) => {
    const next = new URLSearchParams(params)
    const trimmed = id.trim()
    if (trimmed === '') next.delete('sibling')
    else next.set('sibling', trimmed)
    setParams(next, { replace: true })
  }

  // Theme 2 honesty pass: derive the pane from the pure resolver so an API/network failure
  // on the auto-suggest can NEVER be shown as "no comparable sibling exists" (a false
  // negative). Only an explicit found=false is allowed to render the domain empty state.
  const pane = selectComparePane({
    siblingSelected: effectiveSibling !== undefined,
    nearest: { isPending: nearest.isPending, isError: nearest.isError, found: nearest.data?.found },
    diff: { isPending: diff.isPending, isError: diff.isError, error: diff.error },
  })

  return (
    <div className="comparison-tab">
      <SiblingPicker
        current={effectiveSibling}
        usingSuggested={usingSuggested}
        suggested={suggested}
        suggestedRef={nearest.data?.found === true ? nearest.data.sibling : undefined}
        nearestPending={nearest.isPending}
        nearestFound={nearest.data?.found}
        onApply={applySibling}
      />

      {pane.kind === 'nearest-pending' ? (
        <p className="zero-state">Finding a comparable sibling…</p>
      ) : pane.kind === 'nearest-error' ? (
        <div className="error-banner" role="alert">
          <strong>Couldn’t query siblings.</strong> {nearest.error?.message}. This is an API or
          network failure — it does <em>not</em> mean no comparable sibling exists. Paste a
          known-good process-instance id above to compare directly, or retry once the engine is
          reachable again.
        </div>
      ) : pane.kind === 'no-sibling' ? (
        <p className="zero-state">
          No completed instance of this definition version was found to auto-compare. Paste the
          process-instance id of a known-good run above to compare against it.
        </p>
      ) : pane.kind === 'diff-pending' ? (
        <p className="zero-state">Computing the diff against {effectiveSibling}…</p>
      ) : pane.kind === 'diff-error' ? (
        pane.errorKind === 'not-found' ? (
          <div className="error-banner" role="alert">
            No instance <code>{effectiveSibling}</code> was found on this engine to compare against.
            Check the process-instance id — it must be a completed run on the same engine.
          </div>
        ) : (
          <div className="error-banner" role="alert">
            <strong>Failed to compute the diff.</strong> {diff.error?.message}. This is an API or
            network failure, not a verdict on the sibling — retry once the engine is reachable.
          </div>
        )
      ) : (
        <>
          {diff.data?.sameDefinition === false && (
            <div className="partial-banner" role="alert">
              This sibling is a <strong>different definition version</strong> — the paths and
              variables may not line up. Comparisons are most meaningful within one version.
            </div>
          )}
          <CompareHeader subject={diff.data?.subject} sibling={diff.data?.sibling} />

          <section className="comparison-section" aria-label="Path divergence on the diagram">
            <h3>Path — where the two runs diverged</h3>
            <p className="strip-note">
              The divergence is overlaid on the process diagram above — the failed run’s unique
              steps carry ▲, the sibling’s carry △.
            </p>
            <ul className="diverge-legend" aria-label="Path divergence legend">
              <li>
                <span className="diverge-glyph diverge-glyph-subject">▲</span> only the failed run
              </li>
              <li>
                <span className="diverge-glyph diverge-glyph-sibling">△</span> only the sibling
              </li>
            </ul>
          </section>

          <section className="comparison-section" aria-label="Variable differences">
            <h3>Variables</h3>
            {diff.data?.previewCappedPresent === true && (
              <p className="strip-note">
                Some variables exceed the preview cap and are compared by size only — the full
                values were deliberately not fetched.
              </p>
            )}
            <VariableDiffPane variables={diff.data?.variables ?? []} />
          </section>

          <section className="comparison-section" aria-label="Per-activity timing">
            <h3>Timing — per activity</h3>
            <TimingBars timings={diff.data?.timings ?? []} />
          </section>
        </>
      )}
    </div>
  )
}

function SiblingPicker({
  current,
  usingSuggested,
  suggested,
  suggestedRef,
  nearestPending,
  nearestFound,
  onApply,
}: {
  current: string | undefined
  usingSuggested: boolean
  suggested: string | undefined
  suggestedRef: SiblingInstanceRef | undefined
  nearestPending: boolean
  nearestFound: boolean | undefined
  onApply: (id: string) => void
}) {
  const [draft, setDraft] = useState('')
  return (
    <form
      className="sibling-picker"
      onSubmit={(event) => {
        event.preventDefault()
        onApply(draft)
      }}
    >
      <div className="sibling-picker-current">
        {current !== undefined ? (
          <>
            <span className="value-muted">Comparing against sibling</span> <code>{current}</code>
            {usingSuggested && (
              <span
                className="sibling-suggested-chip"
                title="the most recent completed run of this definition version"
              >
                auto-suggested
                {suggestedRef?.endTime !== undefined && (
                  <>
                    {' · completed '}
                    <Ts iso={suggestedRef.endTime} />
                  </>
                )}
              </span>
            )}
          </>
        ) : nearestPending ? (
          <span className="value-muted">Looking for a comparable sibling…</span>
        ) : (
          <span className="value-muted">No sibling selected</span>
        )}
      </div>
      <label className="sibling-picker-input">
        <span className="value-muted">Compare with a different sibling</span>
        <input
          type="text"
          aria-label="sibling process instance id"
          placeholder="process instance id of a known-good run"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value)
          }}
        />
      </label>
      <button type="submit" className="copy-btn" disabled={draft.trim() === ''}>
        Compare
      </button>
      {!usingSuggested && suggested !== undefined && nearestFound === true && (
        <button
          type="button"
          className="copy-btn"
          onClick={() => {
            onApply('')
          }}
        >
          use suggested
        </button>
      )}
    </form>
  )
}

/** Identity strip so the operator sees exactly which two runs are being compared. */
function CompareHeader({
  subject,
  sibling,
}: {
  subject: SiblingInstanceRef | undefined
  sibling: SiblingInstanceRef | undefined
}) {
  return (
    <div className="compare-header">
      <div className="compare-side">
        <span className="compare-role">▲ This run (failed)</span>
        <code>{subject?.processInstanceId}</code>
        <span className="value-muted">
          {subject?.businessKey !== undefined && `key ${subject.businessKey} · `}
          started <Ts iso={subject?.startTime} relative />
          {subject?.ended === false && ' · still open'}
        </span>
      </div>
      <div className="compare-side">
        <span className="compare-role">△ Sibling (succeeded)</span>
        <code>{sibling?.processInstanceId}</code>
        <span className="value-muted">
          {sibling?.businessKey !== undefined && `key ${sibling.businessKey} · `}
          {sibling?.endTime !== undefined ? (
            <>
              {'completed '}
              <Ts iso={sibling.endTime} relative />
            </>
          ) : (
            'completion time unknown'
          )}
          {sibling?.durationMs !== undefined &&
            ` · took ${formatSeconds(Math.round(sibling.durationMs / 1000))}`}
        </span>
      </div>
    </div>
  )
}
