/**
 * Fleet-grant chip (IDP-SECURITY.md §12, ⚠️ UX). A fleet grant (REGISTRY_ADMIN / ACCESS_ADMIN)
 * must never read as a per-engine ADMIN in a flat, sortable table where a colour "band" evaporates.
 * So the distinction is INTRINSIC: an in-chip shape/glyph (◆) + a literal "FLEET" token + the kind
 * name — all textual, so it survives sort/filter and screen-reader linearization (not colour alone).
 * ACCESS_ADMIN is the loudest (apex); REGISTRY_ADMIN is the vault grant.
 */
export function FleetChip({ kind }: { kind: string }) {
  const apex = kind === 'ACCESS_ADMIN'
  return (
    <span
      className={`fleet-chip ${apex ? 'fleet-apex' : 'fleet-registry'}`}
      aria-label={`FLEET grant: ${kind}${apex ? ' (apex)' : ''}`}
    >
      <span aria-hidden="true" className="fleet-glyph">
        ◆
      </span>
      <span className="fleet-token">FLEET</span>
      <span className="fleet-kind">{kind}</span>
    </span>
  )
}
