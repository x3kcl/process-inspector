import { useState } from 'react'
import type { VariableDelta } from '../../api/model'
import { changeGlyph, changeLabel, isDivergent, renderValue } from './diffFormat'

interface Props {
  variables: VariableDelta[]
}

/**
 * Side-by-side variable diff (SPEC §5.2). Divergent keys first and shown by default; the
 * unchanged rows collapse behind a toggle so the signal isn't buried. Every row leads with a
 * glyph (±/+/−/~/=) so the change kind reads without relying on colour (§10a).
 */
export function VariableDiffPane({ variables }: Props) {
  const [showSame, setShowSame] = useState(false)
  const divergent = variables.filter((v) => v.change !== undefined && isDivergent(v.change))
  const same = variables.filter((v) => v.change === 'SAME')
  const shown = showSame ? [...divergent, ...same] : divergent

  if (variables.length === 0) {
    return <p className="zero-state">Neither run recorded any historic variables.</p>
  }
  return (
    <div className="var-diff">
      <div className="var-diff-summary">
        <span>
          {divergent.length} differing · {same.length} identical
        </span>
        {same.length > 0 && (
          <label className="var-diff-toggle">
            <input
              type="checkbox"
              checked={showSame}
              onChange={(event) => {
                setShowSame(event.target.checked)
              }}
            />
            show identical variables
          </label>
        )}
      </div>
      {divergent.length === 0 && !showSame ? (
        <p className="zero-state">Every recorded variable matches the sibling.</p>
      ) : (
        <table className="var-diff-table">
          <thead>
            <tr>
              <th scope="col">
                <span className="visually-hidden">Change</span>
              </th>
              <th scope="col">Variable</th>
              <th scope="col">This (failed) run</th>
              <th scope="col">Sibling</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((delta) => (
              <tr
                key={delta.name ?? '?'}
                className={`var-diff-row change-${(delta.change ?? 'SAME').toLowerCase()}`}
              >
                <td
                  className="var-diff-glyph"
                  title={delta.change !== undefined ? changeLabel(delta.change) : ''}
                >
                  {delta.change !== undefined ? changeGlyph(delta.change) : ''}
                </td>
                <td className="var-diff-name">
                  <code>{delta.name}</code>
                  <span className="var-diff-type value-muted">
                    {' '}
                    {delta.subject?.type ?? delta.sibling?.type}
                  </span>
                </td>
                <td className="var-diff-value">{renderValue(delta.subject)}</td>
                <td className="var-diff-value">{renderValue(delta.sibling)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
