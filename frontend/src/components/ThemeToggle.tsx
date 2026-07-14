// R-UXQ-08 (SPEC §10a, issue #104 slice 2b): the persisted dark-theme control, mirroring
// ZoneToggle.tsx's structure exactly. A labeled segmented control (never icon-only, this
// codebase's explicit convention per Segmented.tsx) so the current preference is always
// readable in text — System / Light / Dark.
import { setThemePreference, useThemePreference } from '../lib/theme'
import { Segmented } from './Segmented'

export function ThemeToggle() {
  const theme = useThemePreference()
  return (
    <span className="theme-toggle">
      <Segmented
        ariaLabel="Color theme"
        options={[
          { value: 'system', label: 'System', title: 'Follow the OS/browser color scheme' },
          { value: 'light', label: 'Light', title: 'Always use the light theme' },
          { value: 'dark', label: 'Dark', title: 'Always use the dark theme' },
        ]}
        value={theme}
        onChange={setThemePreference}
      />
    </span>
  )
}
