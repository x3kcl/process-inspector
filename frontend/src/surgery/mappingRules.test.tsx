// @vitest-environment jsdom
// Issue #498: activityMappings fan rules. One-to-one, fan-out (one→many) and fan-in
// (many→one) compile to the three shapes MigrationMapping.form() accepts. Many-to-many
// is refused with a reason. Expert reset returns the JSON the pairs generate.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { MappingEditor } from './MappingEditor'
import {
  MANY_TO_MANY_REASON,
  addPair,
  compileMappings,
  expertIsDirty,
  parseMappingJson,
  resetToGenerated,
} from './mappingRules'
import type { ActivityPair } from './mappingRules'

afterEach(() => {
  cleanup()
})

const REVIEW = { id: 'reviewTask', name: 'Review' }
const APPROVE = { id: 'approveTask', name: 'Approve' }
const SPLIT = { id: 'split', name: 'Split' }
const X = { id: 'x', name: 'X' }
const Y = { id: 'y', name: 'Y' }
const A = { id: 'a', name: 'A' }
const B = { id: 'b', name: 'B' }
const MERGED = { id: 'merged', name: 'Merged' }

function textareaValue(element: HTMLElement): string {
  if (!(element instanceof HTMLTextAreaElement)) {
    throw new Error('mapping json is not a textarea')
  }
  return element.value
}

function Harness({
  sources,
  targets,
  findings = [],
}: {
  sources: { id: string; name?: string; type?: string }[]
  targets: { id: string; name?: string; type?: string }[]
  findings?: { activityId?: string; severity?: string; detail?: string }[]
}) {
  const [pairs, setPairs] = useState<ActivityPair[]>([])
  const [expertText, setExpertText] = useState<string | null>(null)
  return (
    <MappingEditor
      sources={sources}
      targets={targets}
      findings={findings}
      pairs={pairs}
      onPairsChange={setPairs}
      expertText={expertText}
      onExpertTextChange={setExpertText}
    />
  )
}

describe('migration mapping fan rules (#498)', () => {
  it('adds a one-to-one pair', () => {
    const pairs = addPair([], 'reviewTask', 'approveTask')
    expect(compileMappings(pairs)).toEqual({
      ok: true,
      mappings: [{ fromActivityId: 'reviewTask', toActivityId: 'approveTask' }],
    })
  })

  it('compiles fan-out as one source to many targets', () => {
    const pairs = addPair(addPair([], 'split', 'x'), 'split', 'y')
    expect(compileMappings(pairs)).toEqual({
      ok: true,
      mappings: [{ fromActivityId: 'split', toActivityIds: ['x', 'y'] }],
    })
  })

  it('compiles fan-in as many sources to one target', () => {
    const pairs = addPair(addPair([], 'a', 'merged'), 'b', 'merged')
    expect(compileMappings(pairs)).toEqual({
      ok: true,
      mappings: [{ fromActivityIds: ['a', 'b'], toActivityId: 'merged' }],
    })
  })

  it('refuses many-to-many with a reason', () => {
    const pairs = addPair(addPair(addPair([], 'a', 'x'), 'a', 'y'), 'b', 'x')
    expect(compileMappings(pairs)).toEqual({ ok: false, reason: MANY_TO_MANY_REASON })
  })

  it('reset-to-generated drops expert text back to the compiled pairs', () => {
    const pairs = addPair([], 'reviewTask', 'approveTask')
    const generated = resetToGenerated(pairs)
    const expert = generated.replace('approveTask', 'tampered')
    expect(expertIsDirty(generated, expert)).toBe(true)
    expect(resetToGenerated(pairs)).toBe(generated)
    expect(resetToGenerated(pairs)).not.toContain('tampered')
  })
})

describe('parseMappingJson (#498)', () => {
  it('accepts the three engine forms and refuses many-to-many', () => {
    expect(parseMappingJson('[{"fromActivityId":"a","toActivityId":"b"}]')).toEqual({
      ok: true,
      mappings: [{ fromActivityId: 'a', toActivityId: 'b' }],
    })
    expect(parseMappingJson('[{"fromActivityId":"split","toActivityIds":["x","y"]}]')).toEqual({
      ok: true,
      mappings: [{ fromActivityId: 'split', toActivityIds: ['x', 'y'] }],
    })
    expect(parseMappingJson('[{"fromActivityIds":["a","b"],"toActivityId":"merged"}]')).toEqual({
      ok: true,
      mappings: [{ fromActivityIds: ['a', 'b'], toActivityId: 'merged' }],
    })
    expect(parseMappingJson('[{"fromActivityIds":["a","b"],"toActivityIds":["x","y"]}]')).toEqual({
      ok: false,
      reason: MANY_TO_MANY_REASON,
    })
  })
})

describe('MappingEditor (#498)', () => {
  it('the target select adds a one-to-one mapping the expert pane will submit', () => {
    render(<Harness sources={[REVIEW]} targets={[APPROVE]} />)
    fireEvent.change(screen.getByRole('combobox', { name: 'target for reviewTask' }), {
      target: { value: 'approveTask' },
    })
    expect(screen.getByRole('button', { name: 'Remove reviewTask to approveTask' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Expert' }))
    const box = screen.getByRole('textbox', { name: 'mapping json' })
    expect(textareaValue(box)).toBe(
      resetToGenerated([{ fromActivityId: 'reviewTask', toActivityId: 'approveTask' }]),
    )
  })

  it('Add to map adds a one-to-one pair', () => {
    render(<Harness sources={[REVIEW]} targets={[APPROVE]} />)
    fireEvent.change(screen.getByRole('combobox', { name: 'pair source' }), {
      target: { value: 'reviewTask' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'pair target' }), {
      target: { value: 'approveTask' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add to map' }))
    expect(screen.getByRole('button', { name: 'Remove reviewTask to approveTask' })).toBeTruthy()
  })

  it('an extra target select fans one source out to many targets', () => {
    render(<Harness sources={[SPLIT]} targets={[X, Y]} />)
    fireEvent.change(screen.getByRole('combobox', { name: 'target for split' }), {
      target: { value: 'x' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add another target for split' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'additional target 2 for split' }), {
      target: { value: 'y' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Expert' }))
    const box = screen.getByRole('textbox', { name: 'mapping json' })
    expect(textareaValue(box)).toBe(
      resetToGenerated([
        { fromActivityId: 'split', toActivityId: 'x' },
        { fromActivityId: 'split', toActivityId: 'y' },
      ]),
    )
  })

  it('two sources choosing the same target fan in', () => {
    render(<Harness sources={[A, B]} targets={[MERGED]} />)
    fireEvent.change(screen.getByRole('combobox', { name: 'target for a' }), {
      target: { value: 'merged' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'target for b' }), {
      target: { value: 'merged' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Expert' }))
    const box = screen.getByRole('textbox', { name: 'mapping json' })
    expect(textareaValue(box)).toBe(
      resetToGenerated([
        { fromActivityId: 'a', toActivityId: 'merged' },
        { fromActivityId: 'b', toActivityId: 'merged' },
      ]),
    )
  })

  it('shows the many-to-many refusal as text', () => {
    render(<Harness sources={[A, B]} targets={[X, Y]} />)
    fireEvent.change(screen.getByRole('combobox', { name: 'target for a' }), {
      target: { value: 'x' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add another target for a' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'additional target 2 for a' }), {
      target: { value: 'y' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'target for b' }), {
      target: { value: 'x' },
    })
    expect(screen.getByRole('alert').textContent).toContain(MANY_TO_MANY_REASON)
  })

  it('reset asks before discarding unsaved expert text, then restores the generated pairs', () => {
    render(<Harness sources={[REVIEW]} targets={[APPROVE]} />)
    fireEvent.change(screen.getByRole('combobox', { name: 'target for reviewTask' }), {
      target: { value: 'approveTask' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Expert' }))
    const box = screen.getByRole('textbox', { name: 'mapping json' })
    const generated = textareaValue(box)
    fireEvent.change(box, {
      target: { value: '[{"fromActivityId":"reviewTask","toActivityId":"tampered"}]' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Reset to generated' }))
    expect(screen.getByText('Discard unsaved expert edits?')).toBeTruthy()
    expect(textareaValue(screen.getByRole('textbox', { name: 'mapping json' }))).toContain(
      'tampered',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }))
    expect(textareaValue(screen.getByRole('textbox', { name: 'mapping json' }))).toBe(generated)
  })

  it('labels blocker and warning findings with words', () => {
    render(
      <Harness
        sources={[
          { id: 'miScope', name: 'Scope' },
          { id: 'bndC', name: 'Boundary' },
        ]}
        targets={[{ id: 'stepM', name: 'Step M' }]}
        findings={[
          { activityId: 'miScope', severity: 'BLOCKER_ADVICE', detail: 'nothing to send' },
          { activityId: 'bndC', severity: 'WARNING', detail: 'deadline gone' },
        ]}
      />,
    )
    expect(screen.getByText('blocker')).toBeTruthy()
    expect(screen.getByText('warning')).toBeTruthy()
  })
})
