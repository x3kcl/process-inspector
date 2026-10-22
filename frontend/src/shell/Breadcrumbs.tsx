import type { Crumb } from './breadcrumbs'

export function Breadcrumbs({ segments }: { segments: Crumb[] }) {
  if (segments.length === 0) return null
  return (
    <nav className="breadcrumbs" aria-label="Breadcrumb">
      <ol>
        {segments.map((segment, index) => {
          const last = index === segments.length - 1
          return (
            <li key={`${segment.label}-${String(index)}`}>
              {last || segment.to === undefined ? (
                <span aria-current={last ? 'page' : undefined}>{segment.label}</span>
              ) : (
                <a href={segment.to}>{segment.label}</a>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
