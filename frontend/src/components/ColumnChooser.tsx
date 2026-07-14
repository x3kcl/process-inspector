// R-UXQ-09 (SPEC §10a, issue #104 slice 4/6): the results-grid column-visibility chooser.
// AG Grid COMMUNITY has no built-in column tool panel (that's Enterprise-only, and ADR-002
// mandates Community-only — see ResultsGrid.tsx's own top-of-file comment), so this is a
// small custom dropdown: a toggle button + a panel of real checkboxes (checkbox+label is
// natively accessible and needs no extra ARIA menu-role work) plus a reset action. Grid-
// scoped, not a global app preference like ThemeToggle/DensityToggle — it lives next to
// ResultsGrid in SearchPage's toolbar, not the header.
import { useEffect, useRef, useState } from 'react'
import { resetColumnVisibility, setColumnHidden, useHiddenColumns } from '../lib/columnVisibility'

export interface ChooserColumn {
  /** The ColDef colId/field used as the `hide` lookup key in ResultsGrid's columns memo. */
  id: string
  label: string
}

// Must match ResultsGrid.tsx's HIDEABLE column set exactly (locked columns — open, protected,
// engineName, status — are never offered here; see the note rendered in the panel below).
// Exported (#197): the same allowlist a decoded URL `cols` suggestion is validated against —
// this is the client-side equivalent of the server-side allowlist a DB-column-backed design
// would have needed, since under the URL-encoded mechanism the BFF never sees this field at
// all (docs/SHARED-VIEWS.md §8).
export const HIDEABLE_COLUMNS: ChooserColumn[] = [
  { id: 'processInstanceId', label: 'Process ID' },
  { id: 'businessKey', label: 'Business Key' },
  { id: 'definition', label: 'Definition' },
  { id: 'startTime', label: 'Start Time' },
  { id: 'failureTime', label: 'Failure Time' },
  { id: 'currentActivityOrError', label: 'Current Activity / Error' },
]

export function ColumnChooser() {
  const hidden = useHiddenColumns()
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)

  // Close on Escape and on click-outside — no existing shared pattern for a non-modal
  // dropdown in this codebase (ModalShell's Escape handling is scoped to a full <dialog>),
  // so this is the standard document-listener approach, only attached while open. Escape
  // also restores focus to the toggle button — mirrors ModalShell.tsx's focus-restore
  // precedent; without this, an Escape pressed while focus sits on a checkbox/reset button
  // inside the panel would drop focus to <body> once that element unmounts (review finding,
  // #104 slice 4/6). Restoring to the toggle button specifically (via toggleRef), not
  // "whatever had focus before the panel opened" — the latter depends on the browser having
  // already moved focus to the button as part of the click that opened it, which isn't
  // reliable to assume across environments. Click-outside deliberately does NOT restore
  // focus — the user just clicked something else, so forcing focus back would fight that.
  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        toggleRef.current?.focus()
      }
    }
    const onPointerDown = (event: MouseEvent) => {
      if (
        rootRef.current !== null &&
        event.target instanceof Node &&
        !rootRef.current.contains(event.target)
      ) {
        setOpen(false)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('mousedown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('mousedown', onPointerDown)
    }
  }, [open])

  return (
    <div className="column-chooser" ref={rootRef}>
      <button
        ref={toggleRef}
        type="button"
        aria-expanded={open}
        aria-haspopup="true"
        onClick={() => {
          setOpen((wasOpen) => !wasOpen)
        }}
      >
        Columns ▾
      </button>
      {open && (
        <div className="column-chooser-panel" role="region" aria-label="Choose visible columns">
          <p className="column-chooser-note">
            Open, Engine, Status, and Protected are always shown — they're the row-open affordance
            and the R-SAFE-05 honesty indicators, so they can't be hidden.
          </p>
          <ul className="column-chooser-list">
            {HIDEABLE_COLUMNS.map((column) => {
              const inputId = `column-chooser-${column.id}`
              const visible = !hidden.has(column.id)
              return (
                <li key={column.id}>
                  <input
                    type="checkbox"
                    id={inputId}
                    checked={visible}
                    onChange={(event) => {
                      setColumnHidden(column.id, !event.target.checked)
                    }}
                  />
                  <label htmlFor={inputId}>{column.label}</label>
                </li>
              )
            })}
          </ul>
          <button
            type="button"
            className="column-chooser-reset"
            disabled={hidden.size === 0}
            onClick={() => {
              resetColumnVisibility()
            }}
          >
            Reset to default
          </button>
        </div>
      )}
    </div>
  )
}
