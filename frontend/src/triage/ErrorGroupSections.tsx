// R-BAU-01: the landing's group split — active cards first, acknowledged groups collapsed
// into a labeled "Acknowledged (N)" tail that is NEVER hidden (a <details>, the house
// collapse idiom — the count stays visible even while folded, so the landing cannot rot
// into a silently-muted alarm list). Auto-resurfaced groups rejoin the ACTIVE list with
// their badge; only quiet acknowledgments collapse.
import type { EngineDto, ErrorGroup } from '../api/model'
import { formatCount } from '../lib/format'
import { splitAcknowledged } from './ackState'
import { ErrorGroupCard } from './ErrorGroupCard'

interface Props {
  groups: ErrorGroup[]
  enginesById: Map<string, EngineDto>
  lowerBound: boolean
}

export function ErrorGroupSections({ groups, enginesById, lowerBound }: Props) {
  const { active, acknowledged } = splitAcknowledged(groups)
  return (
    <>
      {active.length === 0 && acknowledged.length > 0 && (
        <div className="zero-state">
          Every failure group is acknowledged — see the section below. Acknowledged groups
          auto-resurface here when they grow, fail on a new version, or their ack expires.
        </div>
      )}
      {active.map((group, index) => (
        <ErrorGroupCard
          key={group.signatureHash ?? String(index)}
          group={group}
          enginesById={enginesById}
          lowerBound={lowerBound}
        />
      ))}
      {acknowledged.length > 0 && (
        <details className="ledger-group acknowledged-section">
          <summary>
            Acknowledged <span className="group-count">({formatCount(acknowledged.length)})</span>
          </summary>
          {acknowledged.map((group, index) => (
            <ErrorGroupCard
              key={group.signatureHash ?? String(index)}
              group={group}
              enginesById={enginesById}
              lowerBound={lowerBound}
            />
          ))}
        </details>
      )}
    </>
  )
}
