import { useState } from 'react'

/** Explicit copy affordance (AG Grid Community: no range selection/clipboard, ADR-002). */
export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
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
    <button type="button" className="copy-btn" onClick={copy}>
      {copied ? 'Copied ✓' : label}
    </button>
  )
}
