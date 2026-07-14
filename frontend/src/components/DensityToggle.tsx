// R-UXQ-09 (SPEC §10a, issue #104 slice 3/6): the persisted grid-density control, mirroring
// ThemeToggle.tsx's structure exactly. A labeled segmented control (never icon-only, this
// codebase's explicit convention per Segmented.tsx) so the current density is always
// readable in text — Comfortable / Compact.
import { setDensity, useDensity } from '../lib/density'
import { Segmented } from './Segmented'

export function DensityToggle() {
  const density = useDensity()
  return (
    <span className="density-toggle">
      <Segmented
        ariaLabel="Grid density"
        options={[
          { value: 'comfortable', label: 'Comfortable', title: 'Roomier grid rows (default)' },
          { value: 'compact', label: 'Compact', title: 'Tighter grid rows — more visible at once' },
        ]}
        value={density}
        onChange={setDensity}
      />
    </span>
  )
}
