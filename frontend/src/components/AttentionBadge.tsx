// The attention-ranking marker + its VISIBLE per-card rationale (ALARM-COST-MODEL.md §4.3/§11/
// §12.1, issues #354 and #374). Shared between Stage 0's ErrorGroupCard and the Incident Ledger's
// IncidentCard/IncidentDetail — both `ErrorGroup` and `IncidentSummary` carry the SAME optional
// `attention` block (#353), so one component covers both surfaces (mirrors the EnvBadge
// precedent: one cross-module badge over a field two different DTOs share).
//
// #374 correction (ALARM-COST-MODEL.md §12.1): the measured A/B run (§8.8) found the per-card
// rationale living ONLY in a hover `title` actively hurt comprehension — every raw number
// visible on the card face (instance count, DLQ jobs, retrying jobs) can be LARGER on the class
// the ranking places LOWER, and only the hover reconciled them. 3 of 5 arm-B testers picked the
// wrong card on first glance; a free-recall task without re-reading the tooltip mostly scored
// `unsupported`/`partial`. `title` is also invisible to touch, keyboard-only, and most
// screen-reader flows. The fix: the SERVER's per-card rationale (`attention.rationale`) is now
// real visible page text — a `<span>`, not an attribute — while the FIXED, generic glossary
// sentence (what the ranking mechanism means, not per-card evidence) stays hover-only in `title`
// on the pill, unchanged. This is a pure rendering move, not a paragraph: §4.3 already caps the
// rationale at one `·`-joined sentence server-side, so promoting it to visible text does not
// grow it — the same one sentence just moves out of a tooltip.
//
// The rationale is rendered VERBATIM: never recomposed from `factors` client-side, per the
// design's explicit rule that the card shows "real numbers, not vibes" the server computed —
// picking a client-side "dominant factor" would itself BE that forbidden recomposition
// (ALARM-COST-MODEL.md §12.1, option A, rejected for exactly this reason).
import type { AttentionScore } from '../api/model'
import { attentionRationale } from '../incidents/attention'

const GLOSSARY_SENTENCE =
  'Ordered by the expected cost of waiting: freshness and growth, weighted by this class’s ' +
  'historic time-to-resolve — proven self-healers rank lower, and nothing is hidden.'

interface Props {
  attention: AttentionScore | undefined
}

/** Renders NOTHING when `attention` is absent — the shipped, flag-off, EXPECTED-today case
 *  (ALARM-COST-MODEL.md §7/§11: the R1 data-maturity gate is NOT MET, so every response omits
 *  this block in practice). A card with no server score reads exactly as it always did — no
 *  fabricated "why", per the honesty rails every other badge in this codebase follows. */
export function AttentionBadge({ attention }: Props) {
  const rationale = attentionRationale(attention)
  if (rationale === undefined) return null
  return (
    <>
      <span className="attention-badge" title={GLOSSARY_SENTENCE}>
        ranked by attention
      </span>
      {/* #374: visible, not hover-gated — see the file header for why this line exists. */}
      <span className="attention-rationale">{rationale}</span>
    </>
  )
}
