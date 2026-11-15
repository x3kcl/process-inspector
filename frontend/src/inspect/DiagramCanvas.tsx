import { useEffect, useMemo, useRef, useState } from 'react'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import type Canvas from 'diagram-js/lib/core/Canvas'
import type ElementRegistry from 'diagram-js/lib/core/ElementRegistry'
import type Overlays from 'diagram-js/lib/features/overlays/Overlays'
import { DiagramLegend } from './diagram/DiagramLegend'
import { DiagramToolbar } from './diagram/DiagramToolbar'
import { presentKinds } from './diagram/markerKinds'
import type { MarkerKind, MarkerSets } from './diagram/markerKinds'
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
  /** Version diff (#483): elements present only on the diagram being shown. */
  addedActivityIds?: string[]
  /** Version diff (#483): same id, different name, type, or sequence-flow endpoints. */
  changedActivityIds?: string[]
  /**
   * Migration mapping editor (#498): pre-check findings and mapped activities. Stroke style
   * + glyph + label, never hue alone — blocker ✖ (dotted heavy), warning ! (long dash),
   * mapped ↦ (badge only, so it stacks with a finding outline).
   */
  blockerActivityIds?: string[]
  warningActivityIds?: string[]
  mappedActivityIds?: string[]
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

const ZOOM_STEP = 1.25

/** Centred fit; returns the resulting viewbox key. diagram-js centres on any truthy second
 *  argument ('auto' in its docs); its typings only admit a Point. */
function fit(canvas: Canvas): string {
  canvas.zoom('fit-viewport', 'auto' as unknown as Parameters<Canvas['zoom']>[1])
  return viewboxKey(canvas)
}

/** Pan + zoom only: width/height follow the host size, which is what a resize changes. */
function viewboxKey(canvas: Canvas): string {
  const { x, y, scale } = canvas.viewbox()
  return [x, y, scale].join()
}

/**
 * Read-only bpmn-js viewer (SPEC §4 Stage 2: diagram first, top half). NavigatedViewer =
 * zoom/pan without modeling. The bpmn.io watermark the viewer injects is a license term
 * (R-GOV-05) and is left exactly as rendered — nothing here may touch it.
 *
 * Import and decoration are split: `importXML` runs only when `xml` changes; a marker or
 * picker change repaints on the live canvas (pan/zoom untouched, no flicker).
 */
export function DiagramCanvas({
  xml,
  markers,
  onSelectActivity,
  selectedActivityId,
  pickerSourceIds,
  pickerTargetIds,
}: Props) {
  const frameRef = useRef<HTMLDivElement | null>(null)
  const hostRef = useRef<HTMLDivElement | null>(null)
  const viewerRef = useRef<NavigatedViewer | null>(null)
  // Bumped after each successful XML import so the decoration effects re-apply on the
  // fresh canvas (import wipes all markers and overlays).
  const [importGeneration, setImportGeneration] = useState(0)
  const [fullscreen, setFullscreen] = useState(false)
  // React 19 typings dropped the zero-arg useRef<T>() overload — explicit undefined
  // initializer, same runtime behavior as before.
  const prevSelectedRef = useRef<string | undefined>(undefined)
  // Undoes the current decoration; null right after an import (nothing painted yet).
  const clearMarkersRef = useRef<(() => void) | null>(null)
  // The viewbox of our last fit: while the operator has not panned/zoomed away from it, a
  // host resize (the page layout settling after import, full screen) re-fits.
  const fittedRef = useRef<string | null>(null)
  // The click listener is bound ONCE with the viewer; the ref keeps it pointed at the
  // latest callback so a re-render (e.g. fresh marker data) never leaves it stale.
  const onSelectRef = useRef(onSelectActivity)
  onSelectRef.current = onSelectActivity

  const kinds = useMemo(
    () => presentKinds({ ...markers, pickerSourceIds, pickerTargetIds }),
    [markers, pickerSourceIds, pickerTargetIds],
  )

  // One viewer per mount.
  useEffect(() => {
    const host = hostRef.current
    if (host === null) return
    const viewer = new NavigatedViewer({ container: host })
    viewerRef.current = viewer
    viewer.on('element.click', (event: SelectionEvent) => {
      const id = event.element?.id
      if (id !== undefined) onSelectRef.current?.(id)
    })
    const observer =
      typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver(() => {
            const canvas = viewer.get<Canvas>('canvas')
            const untouched = fittedRef.current === viewboxKey(canvas)
            canvas.resized()
            if (untouched) fittedRef.current = fit(canvas)
          })
    observer?.observe(host)
    return () => {
      observer?.disconnect()
      viewerRef.current = null
      viewer.destroy()
    }
  }, [])

  // Import ONLY on an XML change; centred fit on every fresh import.
  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer === null) return
    let cancelled = false
    void viewer
      .importXML(xml)
      .then(() => {
        if (cancelled) return
        fittedRef.current = fit(viewer.get<Canvas>('canvas'))
        // #168 (lower-severity finding, mitigated but not fixed by the diagram already
        // having a plain-text twin elsewhere on the page): bpmn-js injects its own root
        // <svg> with tabindex="0" for keyboard pan/zoom — a real, already-interactive tab
        // stop with no accessible name at all. aria-label only (not role — the element's
        // native interactive pan/zoom semantics stay intact, unlike role="img" which would
        // claim it's a static, non-interactive picture).
        const svg = hostRef.current?.querySelector('svg')
        svg?.setAttribute('aria-label', 'BPMN process diagram — pannable and zoomable canvas')
        clearMarkersRef.current = null
        prevSelectedRef.current = undefined
        setImportGeneration((generation) => generation + 1)
      })
      .catch(() => {
        // Import errors surface via the host page's error state; the canvas stays empty.
      })
    return () => {
      cancelled = true
    }
  }, [xml])

  // Repaint: remove the previous decoration, apply the current one — no re-import.
  useEffect(() => {
    const viewer = viewerRef.current
    if (viewer === null || importGeneration === 0) return
    clearMarkersRef.current?.()
    clearMarkersRef.current = paintMarkers(viewer, kinds, {
      ...markers,
      pickerSourceIds,
      pickerTargetIds,
    })
  }, [kinds, markers, pickerSourceIds, pickerTargetIds, importGeneration])

  // Tab→diagram sync (SPEC §4): the selected activity gets its own marker and is scrolled
  // into view only when it is not already fully visible.
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
      if (!fullyVisible(viewer, selectedActivityId)) canvas.scrollToElement(selectedActivityId)
    } catch {
      // An id the diagram does not know must not kill the render.
    }
  }, [selectedActivityId, importGeneration])

  // Full screen changes the host size: tell the canvas, then re-fit.
  useEffect(() => {
    const onChange = () => {
      setFullscreen(document.fullscreenElement === frameRef.current)
      const canvas = viewerRef.current?.get<Canvas>('canvas')
      if (canvas === undefined) return
      canvas.resized()
      fittedRef.current = fit(canvas)
    }
    document.addEventListener('fullscreenchange', onChange)
    return () => {
      document.removeEventListener('fullscreenchange', onChange)
    }
  }, [])

  const zoomBy = (factor: number) => {
    const canvas = viewerRef.current?.get<Canvas>('canvas')
    canvas?.zoom(canvas.zoom() * factor)
  }

  return (
    <div className="diagram-frame" ref={frameRef}>
      <DiagramToolbar
        onFit={() => {
          const canvas = viewerRef.current?.get<Canvas>('canvas')
          if (canvas !== undefined) fittedRef.current = fit(canvas)
        }}
        onZoomIn={() => {
          zoomBy(ZOOM_STEP)
        }}
        onZoomOut={() => {
          zoomBy(1 / ZOOM_STEP)
        }}
        onFullscreen={
          document.fullscreenEnabled
            ? () => {
                if (document.fullscreenElement === frameRef.current) void document.exitFullscreen()
                else void frameRef.current?.requestFullscreen()
              }
            : undefined
        }
        fullscreen={fullscreen}
      />
      <div className="diagram-canvas" ref={hostRef} />
      <DiagramLegend kinds={kinds} />
    </div>
  )
}

function fullyVisible(viewer: NavigatedViewer, id: string): boolean {
  const element = viewer.get<ElementRegistry>('elementRegistry').get(id) as
    { x?: number; y?: number; width?: number; height?: number } | undefined
  if (element?.x === undefined || element.y === undefined) return false
  const box = viewer.get<Canvas>('canvas').viewbox()
  // A fit rounds its scale, so an edge row can poke out by a fraction of a pixel — 2 screen
  // px of slack keeps a click on such a row from jolting the model.
  const slack = 2 / box.scale
  return (
    element.x >= box.x - slack &&
    element.y >= box.y - slack &&
    element.x + (element.width ?? 0) <= box.x + box.width + slack &&
    element.y + (element.height ?? 0) <= box.y + box.height + slack
  )
}

/**
 * Applies each present marker kind (class + optional role="img" glyph badge) and returns the
 * undo. Best-effort decoration: an id the diagram does not know (collapsed subprocess, stale
 * selection) must not kill the render.
 */
function paintMarkers(viewer: NavigatedViewer, kinds: MarkerKind[], sets: MarkerSets): () => void {
  const canvas = viewer.get<Canvas>('canvas')
  const overlays = viewer.get<Overlays>('overlays')
  const added: [string, string][] = []
  const overlayIds: string[] = []
  for (const kind of kinds) {
    for (const id of sets[kind.key] ?? []) {
      try {
        canvas.addMarker(id, kind.markerClass)
        added.push([id, kind.markerClass])
        if (kind.glyph !== undefined) {
          overlayIds.push(
            overlays.add(id, 'diagram-marker', {
              position: kind.glyph.position,
              html: `<span class="${kind.glyph.className}" role="img" aria-label="${kind.label}" title="${kind.label}">${kind.glyph.text}</span>`,
            }),
          )
        }
      } catch {
        // best-effort decoration
      }
    }
  }
  return () => {
    for (const [id, markerClass] of added) {
      try {
        canvas.removeMarker(id, markerClass)
      } catch {
        // gone with a re-import
      }
    }
    for (const overlayId of overlayIds) overlays.remove(overlayId)
  }
}
