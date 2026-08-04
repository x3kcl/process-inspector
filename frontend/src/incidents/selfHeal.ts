// Self-heal evidence for the RETRYING risk lane (RETRYING-RISK-LANE.md, research track R2,
// #351/#352). Rung-1 pure functions only — no React, no IO — mirroring incidents/sections.ts's
// own idiom: badge copy per §4.1, the risk-ranked sort comparator per §10, and the honesty
// caveats per §5.
//
// STABILITY (§4.2 rule 3, normative): the client renders ONLY the server-served `selfHeal.lane`
// — the dwell/hysteresis state machine that decides WHEN a displayed lane is allowed to change
// lives entirely in the BFF's `SelfHealStatsService`. This module adds NO smoothing, NO local
// dwell counter, and NEVER recomputes a lane from `n`/`healed`/`wilsonLow`/`wilsonHigh` — doing
// so client-side would let the badge flip on every refresh/tab (exactly the instability DMKD
// warns against, and the reason the design put dwell server-side in the first place). If the
// server says `INSUFFICIENT_HISTORY`, the badge says that, even if `n`/`healed` alone might look
// decisive to a naive point-estimate reader.
import type { IncidentSummary, SelfHealStats } from '../api/model'

/** Per-class sample-size floor (RETRYING-RISK-LANE.md §7.1, `inspector.selfheal.floor` default
 *  10). Used ONLY to cap the "X of 10" progress display below — never to decide or recompute
 *  the lane itself (that decision belongs to the server alone, per the module doc comment). */
const SELF_HEAL_FLOOR = 10

export type SelfHealLane =
  'SELF_HEAL_LIKELY' | 'SELF_HEAL_MIXED' | 'SELF_HEAL_UNLIKELY' | 'INSUFFICIENT_HISTORY'

/** Attention-first risk order (RETRYING-RISK-LANE.md §10): classes least likely to self-heal
 *  sort first, classes most likely to self-heal sort last. `INSUFFICIENT_HISTORY` sits in the
 *  middle — it is an evidence state, not a risk claim (§4.2 rule 4), so it is deliberately
 *  neither the most nor the least urgent slot. */
const RISK_RANK: Record<SelfHealLane, number> = {
  SELF_HEAL_UNLIKELY: 0,
  SELF_HEAL_MIXED: 1,
  INSUFFICIENT_HISTORY: 2,
  SELF_HEAL_LIKELY: 3,
}

function riskRank(selfHeal: SelfHealStats | undefined): number {
  const lane = selfHeal?.lane
  if (lane === 'SELF_HEAL_UNLIKELY' || lane === 'SELF_HEAL_MIXED' || lane === 'SELF_HEAL_LIKELY') {
    return RISK_RANK[lane]
  }
  // Absent block or unrecognized lane (feature computation unavailable for this class, or an
  // older/newer server literal this build doesn't know) — fail toward the SAME middle rank as
  // INSUFFICIENT_HISTORY, never toward implying either a good or a bad record.
  return RISK_RANK.INSUFFICIENT_HISTORY
}

/** Risk-ranked ordering ONLY — this function never removes an incident from a list (R-BAU-01
 *  never-hide semantics; issue #352 scope item 2 is explicit: "ordering only, NEVER hiding").
 *  Ties broken by live total desc (design §10: "ties by live total"), then `lastSeen` desc for a
 *  deterministic order among otherwise-identical classes — today, that is almost every incident,
 *  since `INSUFFICIENT_HISTORY` is the measured, expected common case
 *  (reviews/R2-SELFHEAL-BASELINE-2026-08.md). */
export function compareSelfHealRisk(a: IncidentSummary, b: IncidentSummary): number {
  const rankDiff = riskRank(a.selfHeal) - riskRank(b.selfHeal)
  if (rankDiff !== 0) return rankDiff
  const totalDiff = (b.lastTotal ?? 0) - (a.lastTotal ?? 0)
  if (totalDiff !== 0) return totalDiff
  return (b.lastSeen ?? '').localeCompare(a.lastSeen ?? '')
}

export interface SelfHealBadgeContent {
  lane: SelfHealLane
  /** Exact copy per RETRYING-RISK-LANE.md §4.1 — do not reword. */
  text: string
  /** Glossary-linked explanation for the badge's tooltip (SPEC §0 conventions: the plain-
   *  language chip carries a tooltip mirroring the glossary definition, mirrors
   *  inspect/variables/ledger.ts's `chipTooltip` idiom). */
  tooltip: string
}

/** A `≤` bound must still hold after rounding to whole minutes — ceiling, not nearest/floor,
 *  so "typically ≤ N min" never understates the observed p90 (RETRYING-RISK-LANE.md §3.1: tts
 *  copy "always says 'typically ≤ X', never an exact time"). */
function minutesCeil(seconds: number): number {
  return Math.max(1, Math.ceil(seconds / 60))
}

/** Builds the exact per-lane badge copy from RETRYING-RISK-LANE.md §4.1. Returns `undefined`
 *  when there is no recognizable lane to show (the `selfHeal` block is absent — e.g. self-heal
 *  computation is disabled fleet-wide — or carries a lane literal this build does not know);
 *  callers render nothing rather than fabricate a state, per the honesty rails (§5). */
export function selfHealBadgeContent(
  selfHeal: SelfHealStats | undefined,
): SelfHealBadgeContent | undefined {
  if (selfHeal === undefined) return undefined
  const n = selfHeal.n ?? 0
  const healed = selfHeal.healed ?? 0

  switch (selfHeal.lane) {
    case 'SELF_HEAL_LIKELY': {
      const typical =
        selfHeal.ttsP90Seconds !== undefined
          ? `, typically ≤ ${String(minutesCeil(selfHeal.ttsP90Seconds))} min`
          : ''
      return {
        lane: 'SELF_HEAL_LIKELY',
        text: `usually self-heals (${String(healed)}/${String(n)}${typical})`,
        tooltip:
          'This error class usually leaves the RETRYING state on its own — engine-scheduled ' +
          `retries, no operator action — in ${String(healed)} of ${String(n)} observed retrying spells.`,
      }
    }
    case 'SELF_HEAL_MIXED':
      return {
        lane: 'SELF_HEAL_MIXED',
        text: `mixed self-heal record (${String(healed)}/${String(n)})`,
        tooltip:
          'This error class self-heals sometimes and escalates to dead-letter other times — ' +
          `${String(healed)} of ${String(n)} observed retrying spells self-healed.`,
      }
    case 'SELF_HEAL_UNLIKELY':
      return {
        lane: 'SELF_HEAL_UNLIKELY',
        text: `rarely self-heals (${String(healed)}/${String(n)}) — treat like FAILED`,
        tooltip:
          'This error class rarely leaves the RETRYING state on its own — ' +
          `only ${String(healed)} of ${String(n)} observed retrying spells self-healed; most end up ` +
          'dead-lettered.',
      }
    case 'INSUFFICIENT_HISTORY': {
      // The dwell state machine can hold a class at INSUFFICIENT_HISTORY briefly after n has
      // already crossed the floor (§4.2 rule 3) — cap the progress display so it never reads
      // as a nonsensical "12 of 10 spells observed".
      const progress = Math.min(n, SELF_HEAL_FLOOR)
      return {
        lane: 'INSUFFICIENT_HISTORY',
        text: `no reliable self-heal history yet (${String(progress)} of ${String(SELF_HEAL_FLOOR)} spells observed)`,
        tooltip:
          'Not enough completed, unconfounded retrying spells have been recorded for this class ' +
          `to show a self-heal rate (minimum ${String(SELF_HEAL_FLOOR)}). This is expected — it is ` +
          'the normal state for most classes for a long time, not a sign of a problem.',
      }
    }
    default:
      return undefined
  }
}

/** The honesty caveat (RETRYING-RISK-LANE.md §5 "Truncation propagates"): when the window
 *  behind this statistic has any excluded/tainted spell, the badge must say so — never present a
 *  rate computed over a partially-observed window as complete. Returns `undefined` when nothing
 *  was excluded. */
export function selfHealCaveat(selfHeal: SelfHealStats): string | undefined {
  const excluded = selfHeal.excludedSpells ?? 0
  if (selfHeal.truncationTainted === true) {
    return `${String(excluded)} spell${excluded === 1 ? ' is' : 's are'} unmeasurable: truncated scan`
  }
  if (excluded > 0) {
    return (
      `${String(excluded)} spell${excluded === 1 ? '' : 's'} excluded from this statistic ` +
      '(operator-confounded or a sampling gap — not counted toward the rate)'
    )
  }
  return undefined
}

/** R-SAFE-17 scope projection: v1's self-heal rate is fleet-wide per class
 *  (RETRYING-RISK-LANE.md §5 scope note), so a scope-restricted viewer's badge may reflect
 *  spells on engines outside their own scope — the badge tooltip must say so explicitly rather
 *  than presenting a fleet observation as a scoped one. `partial` is the same
 *  `IncidentSummary#partial` signal the card's own "in your scope" badge already uses. */
export function selfHealScopeNote(partial: boolean | undefined): string | undefined {
  return partial === true
    ? 'fleet-wide statistic — may include engines outside your scope'
    : undefined
}
