// The attention-ranking marker + its glossary-linked rationale tooltip (ALARM-COST-MODEL.md
// §4.3/§11, issue #354). Shared between Stage 0's ErrorGroupCard and the Incident Ledger's
// IncidentCard/IncidentDetail — both `ErrorGroup` and `IncidentSummary` carry the SAME optional
// `attention` block (#353), so one component covers both surfaces (mirrors the EnvBadge
// precedent: one cross-module badge over a field two different DTOs share).
//
// The tooltip is the codebase's standard glossary convention (a `title`, not a hyperlink — there
// is no `/glossary` route; see chipTooltip in inspect/variables/ledger.ts and SelfHealBadge's own
// tooltip). It joins a FIXED glossary sentence (ALARM-COST-MODEL.md §4.3's generic explanation of
// what the ordering means — a constant, never derived from `factors`) with the SERVER's own
// per-card rationale, rendered VERBATIM: never recomposed from `factors` client-side, per the
// design's explicit rule that the tooltip shows "real numbers, not vibes" the server computed.
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
    <span className="attention-badge" title={`${GLOSSARY_SENTENCE} ${rationale}`}>
      ranked by attention
    </span>
  )
}
