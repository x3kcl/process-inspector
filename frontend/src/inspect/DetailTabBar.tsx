import { useRef } from 'react'
import type { KeyboardEvent } from 'react'
import type { TabId } from './tabs'
import { TAB_IDS, TAB_LABELS } from './tabs'

interface Props {
  active: TabId
  onSelect: (tab: TabId) => void
}

/**
 * The Stage-2 detail tablist, ARIA-APG-compliant (R-UXQ-02): roving tabindex — only the
 * active tab is in the Tab order — with ArrowLeft/ArrowRight (wrapping) and Home/End
 * moving focus. Activation stays MANUAL (click, or Enter/Space via native button
 * semantics): every tab lazy-loads its chunk + data on open (SPEC §4), so selection
 * following focus would fire a fetch per arrow press.
 */
export function DetailTabBar({ active, onSelect }: Props) {
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([])

  const moveFocus = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const count = TAB_IDS.length
    let next: number
    switch (event.key) {
      case 'ArrowRight':
        next = (index + 1) % count
        break
      case 'ArrowLeft':
        next = (index - 1 + count) % count
        break
      case 'Home':
        next = 0
        break
      case 'End':
        next = count - 1
        break
      default:
        return
    }
    event.preventDefault()
    tabRefs.current[next]?.focus()
  }

  return (
    <div className="tab-bar-wrap">
      <nav className="tab-bar" role="tablist" aria-label="Instance detail tabs">
        {TAB_IDS.map((id, index) => (
          <button
            key={id}
            ref={(element) => {
              tabRefs.current[index] = element
            }}
            type="button"
            role="tab"
            aria-selected={id === active}
            tabIndex={id === active ? 0 : -1}
            className={`tab-button${id === active ? ' tab-active' : ''}`}
            onKeyDown={(event) => {
              moveFocus(event, index)
            }}
            onClick={() => {
              onSelect(id)
            }}
          >
            {TAB_LABELS[id]}
          </button>
        ))}
      </nav>
      {/* Issue #211: the manual-activation pattern above (arrow keys move focus, a SEPARATE
          Enter/Space opens the tab) is the correct ARIA APG tablist convention — screen-reader
          users get this from their AT's own tab-role announcements, but a sighted keyboard-only
          user has no such prompt. Same discoverability doctrine as ResultsGrid's .grid-keys hint. */}
      <span className="tab-bar-keys">
        <kbd>←</kbd>
        <kbd>→</kbd> moves between tabs · <kbd>Enter</kbd> opens the focused tab
      </span>
    </div>
  )
}
