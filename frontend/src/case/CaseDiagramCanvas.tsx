import { useEffect, useRef } from 'react'
import NavigatedViewer from 'cmmn-js/lib/NavigatedViewer'
import type Canvas from 'diagram-js/lib/core/Canvas'
import type Overlays from 'diagram-js/lib/features/overlays/Overlays'
import 'cmmn-js/dist/assets/diagram-js.css'
import 'cmmn-js/dist/assets/cmmn-font/css/cmmn.css'

export interface CaseDiagramMarkers {
  /** Plan-item element ids currently in play (active / async-active / enabled) — highlighted. */
  activePlanItemElementIds: string[]
  /** Plan-item element ids carrying a dead-letter job — red ⚠ badge. */
  failedPlanItemElementIds: string[]
}

interface Props {
  /** Raw CMMN 1.1 XML exactly as deployed, with its `<cmmndi:CMMNDI>` block. */
  xml: string
  /** The case definition's flag: false ⇒ no CMMNDI ⇒ cmmn-js renders nothing (spike Q5). */
  graphicalNotationDefined: boolean
  markers: CaseDiagramMarkers
  onSelectElement?: (elementId: string) => void
  selectedElementId?: string
}

interface SelectionEvent {
  element?: { id?: string }
}

/**
 * Read-only cmmn-js viewer (Case Inspector Phase 2), the CMMN sibling of {@code DiagramCanvas}.
 * NavigatedViewer = zoom/pan without modeling. The bpmn.io "Powered by" watermark the viewer
 * injects is a license term (R-GOV-05) and is left exactly as rendered — nothing here may touch
 * it (build-guarded by scripts/check-bpmn-watermark.mjs).
 *
 * A case whose definition has no graphical notation imports to an EMPTY canvas (cmmn-js does not
 * auto-layout), so when {@code graphicalNotationDefined} is false we render an explicit no-layout
 * state instead of a blank box — the plan-item timeline carries the case's shape in that case.
 */
export function CaseDiagramCanvas({
  xml,
  graphicalNotationDefined,
  markers,
  onSelectElement,
  selectedElementId,
}: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const viewerRef = useRef<NavigatedViewer | null>(null)
  const onSelectRef = useRef(onSelectElement)
  onSelectRef.current = onSelectElement

  // One viewer per mount; only mounted when the model actually has a layout to render.
  useEffect(() => {
    if (!graphicalNotationDefined) return
    const host = hostRef.current
    if (host === null) return
    const viewer = new NavigatedViewer({ container: host })
    viewerRef.current = viewer
    viewer.on('element.click', (event) => {
      const id = (event as SelectionEvent).element?.id
      if (id !== undefined) onSelectRef.current?.(id)
    })
    return () => {
      viewerRef.current = null
      viewer.destroy()
    }
  }, [graphicalNotationDefined])

  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer === null) return
    let cancelled = false
    // cmmn-js 0.20 uses the callback importXML (not a Promise like bpmn-js) — never await it.
    viewer.importXML(xml, (err) => {
      if (cancelled || err) return // a malformed model leaves an empty canvas, never a crash
      const canvas = viewer.get<Canvas>('canvas')
      canvas.zoom('fit-viewport')
      applyMarkers(viewer, markers, selectedElementId)
    })
    return () => {
      cancelled = true
    }
  }, [xml, markers, selectedElementId])

  if (!graphicalNotationDefined) {
    return (
      <div className="case-diagram case-diagram--no-layout" role="note">
        <p className="case-diagram-no-layout-title">No case diagram</p>
        <p className="muted">
          This case definition was deployed without a graphical layout (no CMMNDI), so there is
          nothing to draw. The plan-item timeline below shows the case&apos;s structure and state.
        </p>
      </div>
    )
  }

  return <div className="case-diagram" ref={hostRef} />
}

function applyMarkers(
  viewer: NavigatedViewer,
  markers: CaseDiagramMarkers,
  selectedElementId?: string,
) {
  const canvas = viewer.get<Canvas>('canvas')
  const overlays = viewer.get<Overlays>('overlays')
  for (const id of markers.activePlanItemElementIds) {
    try {
      canvas.addMarker(id, 'marker-active')
    } catch {
      // A plan item the DI does not draw (or a stage plan item) must not kill the render.
    }
  }
  for (const id of markers.failedPlanItemElementIds) {
    try {
      canvas.addMarker(id, 'marker-deadletter')
      overlays.add(id, 'deadletter-badge', {
        position: { top: -10, right: 10 },
        html: '<span class="dlq-overlay" title="dead-letter job on this plan item">⚠</span>',
      })
    } catch {
      // best-effort decoration over the diagram
    }
  }
  if (selectedElementId !== undefined) {
    try {
      canvas.addMarker(selectedElementId, 'marker-selected')
      canvas.scrollToElement(selectedElementId)
    } catch {
      // An id the diagram does not know must not kill the render.
    }
  }
}
