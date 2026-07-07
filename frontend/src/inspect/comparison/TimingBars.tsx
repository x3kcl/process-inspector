import type { TimingDelta } from '../../api/model'
import { barWidthPct, deltaPhrase, timingScale } from './diffFormat'

interface Props {
  timings: TimingDelta[]
}

/**
 * Per-activity duration bars, failed run over sibling (SPEC §5.2) — "where did it stall?".
 * Bars scale to the largest completed duration across both runs; a step that never completed
 * on the failed run (the dead-letter point) shows no bar and says so, which is the signal.
 */
export function TimingBars({ timings }: Props) {
  if (timings.length === 0) {
    return <p className="zero-state">No shared activity history to compare timings on.</p>
  }
  const scale = timingScale(timings)
  return (
    <div className="timing-bars">
      <div className="timing-head">
        <span className="timing-activity">Activity</span>
        <span className="timing-tracks">failed run ▲ · sibling △</span>
        <span className="timing-delta">Δ</span>
      </div>
      {timings.map((t, index) => {
        const stalled = t.subjectUnfinished === true && t.subjectMs === undefined
        return (
          <div
            className={`timing-row${stalled ? ' timing-stalled' : ''}`}
            key={t.activityId ?? `t-${String(index)}`}
          >
            <span className="timing-activity" title={t.activityId}>
              {t.activityName ?? t.activityId ?? '?'}
            </span>
            <span className="timing-tracks">
              <span className="timing-track">
                <span
                  className="timing-bar timing-bar-subject"
                  style={{ width: `${String(barWidthPct(t.subjectMs, scale))}%` }}
                />
                {stalled && (
                  <span className="timing-stall-mark" title="never completed on the failed run">
                    ▲ stalled
                  </span>
                )}
              </span>
              <span className="timing-track">
                <span
                  className="timing-bar timing-bar-sibling"
                  style={{ width: `${String(barWidthPct(t.siblingMs, scale))}%` }}
                />
              </span>
            </span>
            <span
              className={`timing-delta${t.deltaMs !== undefined && t.deltaMs > 0 ? ' timing-slower' : ''}`}
            >
              {deltaPhrase(t)}
            </span>
          </div>
        )
      })}
    </div>
  )
}
