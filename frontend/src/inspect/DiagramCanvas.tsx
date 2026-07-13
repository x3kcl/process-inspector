import { useEffect, useRef, useState } from 'react'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import type Canvas from 'diagram-js/lib/core/Canvas'
import type Overlays from 'diagram-js/lib/features/overlays/Overlays'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'

export interface DiagramMarkers {
  /** Activity ids currently holding a token — green/blue highlight. */
  activeActivityIds: string[]
  /** Activity ids with dead-letter jobs — red ⚠ badge. */
  deadLetterActivityIds: string[]
  /**
   * Sibling-diff divergence (SPEC §5.2/§10a): the two runs are differentiated by STROKE
   * STYLE + glyph, never hue. Subject-only activities get a solid/heavy outline + ▲ badge;
   * sibling-only get a dashed outline + △ badge. Absent on the normal single-instance diagram.
   */
  subjectOnlyActivityIds?: string[]
  siblingOnlyActivityIds?: string[]
}

interface Props {
  /** BPMN 2.0 XML with diagram interchange, exactly as the engine stores it. */
  xml: string
  markers: DiagramMarkers
  /** Synchronized selection with the tabs (SPEC §4): clicks report the activity id. */
  onSelectActivity?: (activityId: string) => void
  /** The activity currently selected FROM the tabs — highlighted and scrolled to. */
  selectedActivityId?: string
  /**
   * Issue #102 diagram-click picker: multi-select node sets a caller (e.g.
   * ChangeStateModal) maintains itself — clicks still report up via onSelectActivity
   * unchanged, this prop only drives the VISUAL marker set. Differentiated by stroke
   * style + glyph (S/T badges), never hue alone, matching the sibling-diff precedent.
   * Absent/empty on every other diagram — a plain array diff against the previous
   * render, so passing the same reference twice is a no-op.
   */
  pickerSourceIds?: string[]
  pickerTargetIds?: string[]
}

interface SelectionEvent {
  element?: { id?: string }
}

/**
 * Read-only bpmn-js viewer (SPEC §4 Stage 2: diagram first, top half). NavigatedViewer =
 * zoom/pan without modeling. The bpmn.io watermark the viewer injects is a license term
 * (R-GOV-05) and is left exactly as rendered — nothing here may touch it.
 */
export function DiagramCanvas({
  xml,
  markers,
  onSelectActivity,
  selectedActivityId,
  pickerSourceIds,
  pickerTargetIds,
}: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const viewerRef = useRef<NavigatedViewer | null>(null)
  // Bumped after each successful XML import so the selection effect re-applies its
  // marker on a fresh canvas (import wipes all markers).
  const [importGeneration, setImportGeneration] = useState(0)
  const prevSelectedRef = useRef<string>()
  const prevPickerSourceRef = useRef<string[]>([])
  const prevPickerTargetRef = useRef<string[]>([])
  // The click listener is bound ONCE with the viewer; the ref keeps it pointed at the
  // latest callback so a re-render (e.g. fresh marker data) never leaves it stale.
  const onSelectRef = useRef(onSelectActivity)
  onSelectRef.current = onSelectActivity

  // One viewer per mount; XML re-imports on change.
  useEffect(() => {
    const host = hostRef.current
    if (host === null) return
    const viewer = new NavigatedViewer({ container: host })
    viewerRef.current = viewer
    viewer.on('element.click', (event: SelectionEvent) => {
      const id = event.element?.id
      if (id !== undefined) onSelectRef.current?.(id)
    })
    return () => {
      viewerRef.current = null
      viewer.destroy()
    }
  }, [])

  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer === null) return
    let cancelled = false
    void viewer
      .importXML(xml)
      .then(() => {
        if (cancelled) return
        const canvas = viewer.get<Canvas>('canvas')
        canvas.zoom('fit-viewport')
        // #168 (lower-severity finding, mitigated but not fixed by the diagram already
        // having a plain-text twin elsewhere on the page): bpmn-js injects its own root
        // <svg> with tabindex="0" for keyboard pan/zoom — a real, already-interactive tab
        // stop with no accessible name at all. aria-label only (not role — the element's
        // native interactive pan/zoom semantics stay intact, unlike role="img" which would
        // claim it's a static, non-interactive picture).
        const svg = hostRef.current?.querySelector('svg')
        svg?.setAttribute('aria-label', 'BPMN process diagram — pannable and zoomable canvas')
        applyMarkers(viewer, markers)
        prevSelectedRef.current = undefined
        prevPickerSourceRef.current = []
        prevPickerTargetRef.current = []
        setImportGeneration((generation) => generation + 1)
      })
      .catch(() => {
        // Import errors surface via the host page's error state; the canvas stays empty.
      })
    return () => {
      cancelled = true
    }
  }, [xml, markers])

  // Tab→diagram sync (SPEC §4): the selected activity gets its own marker and is
  // scrolled into view. Runs after every import (markers are wiped by importXML).
  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer === null || importGeneration === 0) return
    const canvas = viewer.get<Canvas>('canvas')
    const previous = prevSelectedRef.current
    if (previous !== undefined && previous !== selectedActivityId) {
      try {
        canvas.removeMarker(previous, 'marker-selected')
      } catch {
        // The previous id may not exist on a re-imported diagram.
      }
    }
    prevSelectedRef.current = selectedActivityId
    if (selectedActivityId === undefined) return
    try {
      canvas.addMarker(selectedActivityId, 'marker-selected')
      canvas.scrollToElement(selectedActivityId)
    } catch {
      // An id the diagram does not know must not kill the render.
    }
  }, [selectedActivityId, importGeneration])

  // Issue #102 diagram-click picker sync: two independent multi-select sets, each
  // diffed against its own previous render so an unrelated re-render never re-adds
  // markers that are already applied (addMarker on an already-marked element is
  // harmless but pointless churn).
  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer === null || importGeneration === 0) return
    const canvas = viewer.get<Canvas>('canvas')
    const overlays = viewer.get<Overlays>('overlays')
    syncPickerMarkers(canvas, overlays, {
      previous: prevPickerSourceRef.current,
      next: pickerSourceIds ?? [],
      markerClass: 'marker-picker-source',
      overlayKey: 'picker-source',
      glyph: 'S',
      label: 'picked as the source (token canceled here)',
    })
    prevPickerSourceRef.current = pickerSourceIds ?? []
    syncPickerMarkers(canvas, overlays, {
      previous: prevPickerTargetRef.current,
      next: pickerTargetIds ?? [],
      markerClass: 'marker-picker-target',
      overlayKey: 'picker-target',
      glyph: 'T',
      label: 'picked as the target (a fresh token starts here)',
    })
    prevPickerTargetRef.current = pickerTargetIds ?? []
  }, [pickerSourceIds, pickerTargetIds, importGeneration])

  return <div className="diagram-canvas" ref={hostRef} />
}

/**
 * Issue #102 diagram-click picker: diffs `next` against `previous` and adds/removes the
 * marker class + a small lettered overlay badge only for ids that actually changed
 * membership — same containment discipline as applyMarkers (an id the diagram doesn't
 * know, e.g. a stale selection surviving a re-import, must not kill the render).
 */
function syncPickerMarkers(
  canvas: Canvas,
  overlays: Overlays,
  opts: {
    previous: string[]
    next: string[]
    markerClass: string
    overlayKey: string
    glyph: string
    label: string
  },
) {
  const { previous, next, markerClass, overlayKey, glyph, label } = opts
  const nextSet = new Set(next)
  for (const id of previous) {
    if (nextSet.has(id)) continue
    try {
      canvas.removeMarker(id, markerClass)
      overlays.remove({ element: id, type: `${overlayKey}-${id}` })
    } catch {
      // best-effort decoration
    }
  }
  const previousSet = new Set(previous)
  for (const id of next) {
    if (previousSet.has(id)) continue
    try {
      canvas.addMarker(id, markerClass)
      overlays.add(id, `${overlayKey}-${id}`, {
        position: { top: -10, left: -10 },
        html: `<span class="picker-glyph ${markerClass}-glyph" role="img" aria-label="${label}" title="${label}">${glyph}</span>`,
      })
    } catch {
      // best-effort decoration
    }
  }
}

function applyMarkers(viewer: NavigatedViewer, markers: DiagramMarkers) {
  const canvas = viewer.get<Canvas>('canvas')
  const overlays = viewer.get<Overlays>('overlays')
  for (const id of markers.activeActivityIds) {
    try {
      canvas.addMarker(id, 'marker-active')
    } catch {
      // An id the diagram does not know (collapsed subprocess) must not kill the render.
    }
  }
  for (const id of markers.deadLetterActivityIds) {
    try {
      canvas.addMarker(id, 'marker-deadletter')
      // #118 item 4: title is mouse-hover-only — role="img" + aria-label is the SVG-overlay
      // text twin (these are injected as raw HTML via bpmn-js's Overlays API, not JSX, so
      // they can't use the app's usual .visually-hidden pattern).
      overlays.add(id, 'deadletter-badge', {
        position: { top: -10, right: 10 },
        html: '<span class="dlq-overlay" role="img" aria-label="dead-letter job on this activity" title="dead-letter job on this activity">⚠</span>',
      })
    } catch {
      // Same containment: markers are best-effort decoration over the diagram.
    }
  }
  // Divergence overlay (SPEC §5.2): differentiated by stroke style + glyph, not hue.
  for (const id of markers.subjectOnlyActivityIds ?? []) {
    try {
      canvas.addMarker(id, 'marker-diverge-subject')
      overlays.add(id, `diverge-subject-${id}`, {
        position: { bottom: -6, left: -6 },
        html: '<span class="diverge-glyph diverge-glyph-subject" role="img" aria-label="only the failed run took this step" title="only the failed run took this step">▲</span>',
      })
    } catch {
      // best-effort decoration
    }
  }
  for (const id of markers.siblingOnlyActivityIds ?? []) {
    try {
      canvas.addMarker(id, 'marker-diverge-sibling')
      overlays.add(id, `diverge-sibling-${id}`, {
        position: { bottom: -6, left: -6 },
        html: '<span class="diverge-glyph diverge-glyph-sibling" role="img" aria-label="only the successful sibling took this step" title="only the successful sibling took this step">△</span>',
      })
    } catch {
      // best-effort decoration
    }
  }
}
