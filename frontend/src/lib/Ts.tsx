// R-UXQ-03 (SPEC §10a): the one way to render a human timestamp — a <time> element whose
// visible text is the zone-tagged absolute stamp (plus an optional live relative age),
// while title + aria carry the full UTC ISO-8601. Hovering ANY timestamp answers
// "exactly when, unambiguously for a colleague in another timezone" (M9 task 3).
import { useSyncExternalStore } from 'react'
import { CopyButton } from '../components/CopyButton'
import { formatDateTime, formatRelative, toUtcIso, useDisplayZone } from './format'

// One shared 30s ticker for every relative age on screen — never a timer per cell.
let tickerNow = Date.now()
let ticker: ReturnType<typeof setInterval> | null = null
const tickListeners = new Set<() => void>()

function subscribeTick(listener: () => void): () => void {
  if (ticker === null) {
    tickerNow = Date.now()
    ticker = setInterval(() => {
      tickerNow = Date.now()
      for (const tickListener of tickListeners) tickListener()
    }, 30_000)
  }
  tickListeners.add(listener)
  return () => {
    tickListeners.delete(listener)
    if (tickListeners.size === 0 && ticker !== null) {
      clearInterval(ticker)
      ticker = null
    }
  }
}

function useNowMs(): number {
  return useSyncExternalStore(
    subscribeTick,
    () => tickerNow,
    () => tickerNow,
  )
}

interface TsProps {
  iso: string | null | undefined
  /** Append a live relative age ("3h 20m ago" / "in 20m") — the key triage surfaces set this. */
  relative?: boolean
  className?: string
  /** Explicit copy-as-ISO affordance (machine-facing text is always the UTC ISO form). */
  copyIso?: boolean
  /** Test seam / frozen snapshots: overrides the shared ticker's "now". */
  nowMs?: number
}

export function Ts({ iso, relative = false, className, copyIso = false, nowMs }: TsProps) {
  useDisplayZone() // re-render on the one-click UTC toggle — formatDateTime reads the store
  const tick = useNowMs()
  if (iso == null || iso === '') return null
  const utcIso = toUtcIso(iso)
  const absolute = formatDateTime(iso)
  const age = relative ? formatRelative(iso, nowMs ?? tick) : ''
  return (
    <>
      <time
        className={className}
        dateTime={utcIso}
        title={utcIso}
        aria-label={`${absolute} (${utcIso})`}
      >
        {absolute}
        {age !== '' && <span className="ts-relative"> · {age}</span>}
      </time>
      {copyIso && <CopyButton text={utcIso} label="copy ISO" />}
    </>
  )
}
