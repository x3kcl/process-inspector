import { useMemo } from 'react'
import type { PathDivergence } from '../../api/model'
import { DiagramCanvas } from '../DiagramCanvas'
import { useInstanceDiagram } from '../useInstanceQueries'

interface Props {
  engineId: string
  instanceId: string
  divergence: PathDivergence
}

/**
 * The subject's process diagram with the path divergence overlaid (SPEC §5.2): the failed
 * run's unique activities get a solid/heavy outline + ▲, the sibling's unique activities a
 * dashed outline + △ — differentiated by stroke style and glyph, never hue alone (§10a).
 */
export function DivergenceDiagram({ engineId, instanceId, divergence }: Props) {
  const diagram = useInstanceDiagram(engineId, instanceId)
  const markers = useMemo(
    () => ({
      activeActivityIds: [],
      deadLetterActivityIds: [],
      subjectOnlyActivityIds: divergence.onlyInSubject ?? [],
      siblingOnlyActivityIds: divergence.onlyInSibling ?? [],
    }),
    [divergence],
  )

  if (diagram.isPending) return <div className="zero-state">Loading the process diagram…</div>
  if (diagram.isError) {
    return (
      <div className="zero-state zero-warn">
        No diagram: {diagram.error.message}. The variable and timing diffs below are unaffected.
      </div>
    )
  }
  if (diagram.data.xml === undefined || diagram.data.xml === '') {
    return <div className="zero-state">The engine holds no BPMN XML for this definition.</div>
  }
  return (
    <div className="diverge-diagram">
      <ul className="diverge-legend" aria-label="Path divergence legend">
        <li>
          <span className="diverge-glyph diverge-glyph-subject">▲</span> only the failed run
        </li>
        <li>
          <span className="diverge-glyph diverge-glyph-sibling">△</span> only the sibling
        </li>
      </ul>
      <DiagramCanvas xml={diagram.data.xml} markers={markers} />
    </div>
  )
}
