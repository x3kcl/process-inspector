// The single shared "why is this greyed / here's a note" primitive (usability round 1,
// Theme A). Native `disabled` stays on the control — buttons never lose their real
// keyboard/AT semantics. This only makes the SHORT reason visible next to the control
// instead of buried in a hover-only title (which moves to the LONG explanation, SPEC A-copy).
// tone 'gate' is an RBAC/business-logic block (padlock prefix, amber); tone 'info' is a
// neutral aside that renders unconditionally when the caller has something to say.
interface Props {
  id?: string
  text: string
  tone?: 'gate' | 'info'
}

export function ActionHint({ id, text, tone = 'gate' }: Props) {
  return (
    <small id={id} className={`action-hint action-hint-${tone}`} role="note">
      {tone === 'gate' && '🔒 '}
      {text}
    </small>
  )
}
