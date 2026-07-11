// R-UXQ-03 (SPEC §10a): the one-click persisted display-zone control — browser-local by
// default, one click to UTC, honored by the shared formatter everywhere. A labeled
// segmented control (never icon-only) so the CURRENT zone is always readable in text.
import { setDisplayZone, useDisplayZone } from '../lib/format'
import { Segmented } from './Segmented'

export function ZoneToggle() {
  const zone = useDisplayZone()
  return (
    <span className="zone-toggle">
      <Segmented
        ariaLabel="Timestamp display timezone"
        options={[
          {
            value: 'local',
            label: `Local (${localZoneToken()})`,
            title: 'Show every timestamp in your browser timezone',
          },
          {
            value: 'utc',
            label: 'UTC',
            title: 'Show every timestamp in UTC — persisted on this browser',
          },
        ]}
        value={zone}
        onChange={setDisplayZone}
      />
    </span>
  )
}

/** The browser's own short zone token right now (e.g. "GMT+2", "CEST"). */
function localZoneToken(): string {
  const token = new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
    .formatToParts(new Date())
    .find((part) => part.type === 'timeZoneName')?.value
  return token ?? 'local'
}
