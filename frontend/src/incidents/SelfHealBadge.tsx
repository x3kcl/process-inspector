// The self-heal lane badge on RETRYING-bearing incident cards + the incident detail
// (RETRYING-RISK-LANE.md §4.1/§6, issue #352). Renders ONLY the server-served `selfHeal.lane`
// — no client-side recomputation, smoothing, or dwell (see incidents/selfHeal.ts's doc comment;
// RETRYING-RISK-LANE.md §4.2 rule 3 puts the dwell/hysteresis state machine server-side ON
// PURPOSE, since client-side dwell would reset on every refresh/tab).
//
// `INSUFFICIENT_HISTORY` is the expected, common-for-a-long-time state (measured baseline,
// reviews/R2-SELFHEAL-BASELINE-2026-08.md: zero unconfounded completed spells fleet-wide as of
// 2026-08-04) — its styling is deliberately the LEAST visually loud of the four lanes (muted,
// same family as the existing `.status-badge`/`.scope-badge` informational chips) so it reads as
// calm and legible rather than competing for attention once real LIKELY/MIXED/UNLIKELY verdicts
// start appearing at volume.
import type { SelfHealStats } from '../api/model'
import { selfHealBadgeContent, selfHealCaveat, selfHealScopeNote } from './selfHeal'

interface Props {
  selfHeal: SelfHealStats | undefined
  /** IncidentSummary#partial — this class's own counts are scope-restricted (R-SAFE-17); the
   *  self-heal rate itself stays fleet-wide regardless (RETRYING-RISK-LANE.md §5 scope note). */
  partial?: boolean
}

const LANE_CLASS: Record<string, string> = {
  SELF_HEAL_LIKELY: 'lane-likely',
  SELF_HEAL_MIXED: 'lane-mixed',
  SELF_HEAL_UNLIKELY: 'lane-unlikely',
  INSUFFICIENT_HISTORY: 'lane-insufficient',
}

/** Renders nothing when there is no recognizable server-served lane (absent `selfHeal` block —
 *  e.g. self-heal computation disabled fleet-wide) — the honesty rails call for an explicit
 *  "insufficient history" state when the SERVER has one to give, never a client-fabricated one
 *  when it doesn't. */
export function SelfHealBadge({ selfHeal, partial }: Props) {
  const content = selfHealBadgeContent(selfHeal)
  if (content === undefined) return null

  const caveat = selfHeal !== undefined ? selfHealCaveat(selfHeal) : undefined
  const scopeNote = selfHealScopeNote(partial)
  const title = [content.tooltip, caveat, scopeNote]
    .filter((part): part is string => part !== undefined)
    .join(' ')

  return (
    <span className="self-heal-cell">
      <span className={`self-heal-badge ${LANE_CLASS[content.lane]}`} title={title}>
        {content.text}
      </span>
      {selfHeal?.truncationTainted === true && (
        <span className="self-heal-caveat-badge" title={caveat}>
          ≥ scan
        </span>
      )}
    </span>
  )
}
