// @vitest-environment jsdom
// #237 (keyboard-only FIND→FIX efficiency): the inspect page's skip-to-retry control hands
// focus straight to the first dead-letter job action, instead of ~20 Tab presses through
// the vitals-header gauntlet, diagram toggle, tablist and per-row controls.
import { afterEach, describe, expect, it } from 'vitest'
import { DATA_SKIP_SOURCE, focusDeadLetterAction } from './skipToRetry'

afterEach(() => {
  document.body.innerHTML = ''
})

/** The dead-letter lane DOM shape ErrorsJobsTab renders (lane class + .job-actions cell). */
function lanesHtml(actionsCell: string): string {
  return `
    <div class="job-lanes-tab">
      <details class="job-lane lane-deadLetter">
        <table><tbody><tr>
          <td class="id-cell"><button id="copy">copy</button></td>
          <td class="job-actions">${actionsCell}</td>
        </tr></tbody></table>
      </details>
    </div>`
}

describe('focusDeadLetterAction', () => {
  it('focuses the first ENABLED dead-letter action button (the Retry verb)', () => {
    document.body.innerHTML = lanesHtml(
      '<button id="retry">Retry job</button><button id="delete">Delete</button>',
    )
    const target = focusDeadLetterAction()
    expect(target?.id).toBe('retry')
    expect(document.activeElement).toBe(target)
  })

  it('skips a gate-disabled Retry and lands on the next enabled action', () => {
    document.body.innerHTML = lanesHtml(
      '<button id="retry" disabled>Retry job</button><button id="curl">Show as cURL</button>',
    )
    expect(focusDeadLetterAction()?.id).toBe('curl')
  })

  it('falls back to the lanes container (granting tabindex=-1) when every action is gated', () => {
    document.body.innerHTML = lanesHtml('<button disabled>Retry job</button>')
    const target = focusDeadLetterAction()
    expect(target?.className).toBe('job-lanes-tab')
    expect(target?.getAttribute('tabindex')).toBe('-1')
    expect(document.activeElement).toBe(target)
  })

  it('does nothing while the lanes have not rendered yet', () => {
    document.body.innerHTML = '<div class="zero-state">Loading job lanes…</div>'
    expect(focusDeadLetterAction()).toBeNull()
    expect(document.activeElement).toBe(document.body)
  })

  it('never steals focus once the user has Tab’d elsewhere (R-UXQ-06)', () => {
    document.body.innerHTML = `<button id="elsewhere">copy ID</button>${lanesHtml(
      '<button id="retry">Retry job</button>',
    )}`
    const elsewhere = document.getElementById('elsewhere') as HTMLElement
    elsewhere.focus()
    expect(focusDeadLetterAction()).toBeNull()
    expect(document.activeElement).toBe(elsewhere)
  })

  it('still completes when focus sits on the skip control that issued the command', () => {
    document.body.innerHTML = `<button id="skip" ${DATA_SKIP_SOURCE}="true">Skip to failed job</button>${lanesHtml(
      '<button id="retry">Retry job</button>',
    )}`
    ;(document.getElementById('skip') as HTMLElement).focus()
    expect(focusDeadLetterAction()?.id).toBe('retry')
  })
})
