// Labeled segmented control (SPEC §4a): the boolean True/False widget and the
// Form | Source switch both demand a segmented control — NEVER a toggle (toggles read
// as immediate-effect, and nothing applies before confirm) and never icon-only.
interface Option<T extends string> {
  value: T
  label: string
  disabled?: boolean
  title?: string
}

interface Props<T extends string> {
  ariaLabel: string
  options: Option<T>[]
  value: T | undefined
  onChange: (value: T) => void
}

export function Segmented<T extends string>({ ariaLabel, options, value, onChange }: Props<T>) {
  return (
    <div className="segmented" role="group" aria-label={ariaLabel}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          className={`segment${option.value === value ? ' segment-active' : ''}`}
          aria-pressed={option.value === value}
          disabled={option.disabled}
          title={option.title}
          onClick={() => {
            onChange(option.value)
          }}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
