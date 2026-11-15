import { Segmented } from '../../components/Segmented'

/**
 * Diagram view controls (SPEC §4): real buttons with visible text (§4a, never icon-only).
 * The hint names the mouse gestures the canvas already supports.
 */
export function DiagramToolbar({
  onFit,
  onZoomIn,
  onZoomOut,
  onFullscreen,
  fullscreen,
  path,
}: {
  onFit: () => void
  onZoomIn: () => void
  onZoomOut: () => void
  /** Absent where the Fullscreen API is unavailable — the button is then not rendered. */
  onFullscreen?: () => void
  fullscreen: boolean
  /** Execution overlay on/off (#677) — absent when there is no overlay to show. */
  path?: { on: boolean; onChange: (on: boolean) => void }
}) {
  return (
    <div className="diagram-toolbar" role="toolbar" aria-label="diagram view">
      <button type="button" onClick={onFit}>
        Fit
      </button>
      <button type="button" onClick={onZoomIn} aria-label="Zoom in">
        + Zoom in
      </button>
      <button type="button" onClick={onZoomOut} aria-label="Zoom out">
        − Zoom out
      </button>
      {onFullscreen !== undefined && (
        <button type="button" onClick={onFullscreen}>
          {fullscreen ? 'Exit full screen' : 'Full screen'}
        </button>
      )}
      {path !== undefined && (
        <Segmented
          ariaLabel="execution path"
          options={[
            { value: 'on', label: 'Path on' },
            { value: 'off', label: 'Path off' },
          ]}
          value={path.on ? 'on' : 'off'}
          onChange={(value) => {
            path.onChange(value === 'on')
          }}
        />
      )}
      <span className="diagram-toolbar-hint">Ctrl + wheel to zoom · drag or wheel to pan</span>
    </div>
  )
}
