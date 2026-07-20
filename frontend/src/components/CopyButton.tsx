import { useState } from 'react'

/** Explicit copy affordance (AG Grid Community: no range selection/clipboard, ADR-002). */
export function CopyButton({
  text,
  label = 'Copy',
  confirmLabel = 'Copied ✓',
}: {
  text: string
  label?: string
  /**
   * Overrides the post-copy confirmation text (R-UXQ-06, #274). Every call site gets SOME
   * visible confirmation by default — but a call site whose copied content reflects a
   * narrower/filtered view than "everything on screen" should say so here, so the
   * confirmation itself discloses the scope instead of leaving that a silent inference
   * (e.g. "Copied · filtered to your shift ✓").
   */
  confirmLabel?: string
}) {
  const [copied, setCopied] = useState(false)
  const copy = () => {
    void navigator.clipboard
      .writeText(text)
      .then(() => {
        setCopied(true)
        window.setTimeout(() => {
          setCopied(false)
        }, 1500)
      })
      .catch(() => {
        setCopied(false)
      })
  }
  return (
    // aria-live: the confirmation is the ONLY feedback this action gives (#274) — announce
    // the label swap to assistive tech, not just sighted users watching the button repaint.
    <button type="button" className="copy-btn" onClick={copy} aria-live="polite">
      {copied ? confirmLabel : label}
    </button>
  )
}
