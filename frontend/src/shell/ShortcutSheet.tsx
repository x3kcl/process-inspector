import { useEffect, useRef } from 'react'
import type { KeyboardEvent } from 'react'
import { SHORTCUTS } from './shortcuts'

export function ShortcutSheet({ onClose }: { onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement | null>(null)

  useEffect(() => {
    closeRef.current?.focus()
  }, [])

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      onClose()
      return
    }
    if (event.key === 'Tab') {
      event.preventDefault()
      closeRef.current?.focus()
    }
  }

  return (
    <div className="modal-backdrop" onKeyDown={onKeyDown}>
      <div
        className="modal-card shortcut-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Keyboard shortcuts"
      >
        <div className="modal-header">
          <h2>Keyboard shortcuts</h2>
        </div>
        <div className="modal-body">
          <table className="shortcut-table">
            <thead>
              <tr>
                <th scope="col">Keys</th>
                <th scope="col">When</th>
                <th scope="col">Does</th>
              </tr>
            </thead>
            <tbody>
              {SHORTCUTS.map((binding) => (
                <tr key={binding.id}>
                  <td>
                    <kbd>{binding.keys}</kbd>
                  </td>
                  <td>{binding.when}</td>
                  <td>{binding.action}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="modal-footer">
          <button ref={closeRef} type="button" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
