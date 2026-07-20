import type { PartialSummary } from '../search/partials'
import { formatCount } from '../lib/format'
import { glossTechnicalMessage } from '../lib/plainFailure'

interface Props {
  summary: PartialSummary
  onRetry: () => void
}

/**
 * Slim amber banner (SPEC §4 Stage 1): engine failures, truncated DLQ/failing scans and
 * page-cap overflow. Anything counted under one of these is labeled a LOWER BOUND — the
 * grid below shows what could be fetched, never a silent "everything".
 */
export function PartialResultsBanner({ summary, onRetry }: Props) {
  if (!summary.lowerBound) return null
  return (
    <div className="partial-banner" role="status">
      {summary.failed.length > 0 && (
        <span>
          ⚠ {formatCount(summary.okEngines)} of {formatCount(summary.totalEngines)} engines answered{' '}
          <button type="button" onClick={onRetry}>
            Retry
          </button>
        </span>
      )}
      {summary.failed.map((f) => (
        // Theme F: the plain-language gloss is the line an operator reads; the raw
        // exception text stays one click away, never the default view.
        <span key={f.engineId}>
          ⚠ {f.engineId}: {glossTechnicalMessage(f.error)}{' '}
          <details>
            <summary>Technical detail</summary>
            {f.error}
          </details>
        </span>
      ))}
      {summary.truncated.map((t) => (
        <span key={`${t.engineId}:${t.detail}`}>
          ⚠ {t.engineId}: {t.detail} — counts are lower bounds
        </span>
      ))}
      {/* #273: the SAME "N of M fetched" shape covers two different terminal futures for this
          lane — never render them identically. capped=true means Load more will NEVER close
          this gap on this engine (its own depth wall) — the same warning tone as DepthWallNote,
          "narrow your filter" is the only way forward. capped=false means the gap is routine —
          clicking Load more (below the grid) keeps closing it — so the copy points there instead
          of prematurely telling the user to narrow. */}
      {summary.overflowing.map((o) =>
        o.capped ? (
          <span key={o.engineId} className="engine-overflow-capped">
            ⚠ {o.engineId} {formatCount(o.fetched)} of {formatCount(o.total)} fetched — reached the
            paging depth on this engine; Load more can’t reach the rest — narrow your filter
          </span>
        ) : (
          <span key={o.engineId} className="engine-overflow-routine">
            {o.engineId} {formatCount(o.fetched)} of {formatCount(o.total)} fetched so far — Load
            more (below the grid) fetches the rest
          </span>
        ),
      )}
    </div>
  )
}
