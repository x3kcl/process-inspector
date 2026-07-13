// ADMIN-gated entry point for the tier-4 destructive-bulk wizard (SPEC §6/§7, issue #100) — a
// standalone surface next to BulkBar, deliberately NOT folded into its SELECTION/FILTER
// intersection-rule machinery: destructive bulk is FILTER-scoped only, has its own ADMIN door
// floor, and its own typed-count gate (the queue-state verbs' RESPONDER floor never applies).
import { useState } from 'react'
import type { EngineDto, SearchRequest } from '../api/model'
import type { MeDto } from '../api/me'
import { roleOn } from '../api/me'
import { roleAtLeast } from '../actions/catalog'
import { DestructiveBulkWizard } from './DestructiveBulkWizard'

interface Props {
  criteria: SearchRequest | null
  engines: EngineDto[]
  me: MeDto | undefined
  onSubmitted: () => void
}

export function DestructiveBulkEntry({ criteria, engines, me, onSubmitted }: Props) {
  const [open, setOpen] = useState(false)
  if (criteria === null) return null

  // Mirrors the BFF's own fail-fast RBAC check (DestructiveBulkService): named engines, or —
  // an unnamed scope reaching every engine — the whole fleet. Greyed-never-hidden (SPEC §6);
  // the BFF stays the real gate regardless of this hint.
  const targetEngineIds =
    criteria.engineIds ?? engines.map((e) => e.id).filter((id): id is string => id !== undefined)
  const allAdmin = targetEngineIds.every((id) => {
    const role = roleOn(me, id)
    return role !== null && roleAtLeast(role, 'ADMIN')
  })
  const disabled = !allAdmin

  return (
    <>
      <span className="action-slot">
        <button
          type="button"
          className="danger"
          disabled={disabled}
          title={disabled ? 'Blocked: ADMIN on every matching engine is required' : undefined}
          onClick={() => {
            setOpen(true)
          }}
        >
          Destructive bulk…
        </button>
      </span>
      {open && (
        <DestructiveBulkWizard
          criteria={criteria}
          engines={engines}
          onClose={() => {
            setOpen(false)
          }}
          onSubmitted={() => {
            setOpen(false)
            onSubmitted()
          }}
        />
      )}
    </>
  )
}
