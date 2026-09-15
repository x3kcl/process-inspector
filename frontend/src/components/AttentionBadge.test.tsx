// @vitest-environment jsdom
// The attention-ranking badge + its glossary-linked rationale tooltip (ALARM-COST-MODEL.md
// §4.3/§11, issue #354). The absent case is the shipped, flag-off, EXPECTED-today path (§7 gate
// NOT MET) — it gets first billing here, mirroring SelfHealBadge.test.tsx's own convention.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { AttentionScore } from '../api/model'
import { AttentionBadge } from './AttentionBadge'

afterEach(cleanup)

describe('AttentionBadge', () => {
  it('renders nothing when attention is absent (flag off — the expected-today case)', () => {
    const { container } = render(<AttentionBadge attention={undefined} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders nothing when attention is present but carries no rationale (never fabricates one)', () => {
    const scoreOnly: AttentionScore = { score: 4.2 }
    const { container } = render(<AttentionBadge attention={scoreOnly} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders the visible marker and the SERVER rationale VERBATIM as visible text (#374)', () => {
    const attention: AttentionScore = {
      score: 4.2,
      rationale:
        '21 failing · last seen 2 min ago · typically 4 h from first sighting to resolve · no self-heal history.',
    }
    render(<AttentionBadge attention={attention} />)
    expect(screen.getByText('ranked by attention')).not.toBeNull()
    // #374: this must be real page text a non-hovering reader sees, not a `title` attribute —
    // `getByText` only matches rendered text content, so this fails on a hover-only regression.
    expect(
      screen.getByText(
        '21 failing · last seen 2 min ago · typically 4 h from first sighting to resolve · no self-heal history.',
      ),
    ).not.toBeNull()
  })

  it('the tooltip also carries the fixed glossary sentence explaining what the ordering means', () => {
    const attention: AttentionScore = { score: 1, rationale: '8 failing · constant' }
    render(<AttentionBadge attention={attention} />)
    const badge = screen.getByText('ranked by attention')
    expect(badge.getAttribute('title')).toMatch(/expected cost of waiting/)
    expect(badge.getAttribute('title')).toMatch(/nothing is hidden/)
  })

  it('never composes the rationale from factors — only the server string appears', () => {
    // A rationale carrying UNUSUAL wording proves this is rendered verbatim, not re-derived
    // from `factors` (the design's explicit rule, ALARM-COST-MODEL.md §4.1/§11).
    const attention: AttentionScore = {
      score: 2,
      rationale: 'CUSTOM SERVER SENTENCE — do not recompose me',
      factors: { frequency: 99, recency: 99, mttr: 99, selfHeal: 99 },
    }
    render(<AttentionBadge attention={attention} />)
    expect(screen.getByText('CUSTOM SERVER SENTENCE — do not recompose me')).not.toBeNull()
  })

  // #374 (ALARM-COST-MODEL.md §12.1): the measured usability run found the reasoning "lives only
  // in a hover" — 3 of 5 arm-B testers picked the wrong (bigger-number) card on first glance,
  // and a free-recall task without re-reading the tooltip scored mostly `unsupported`/`partial`.
  // A `title` attribute is invisible to touch, keyboard-only, and most screen-reader flows, so
  // the fix must make the SAME server sentence real page content, not just reachable via hover.
  it('#374: the per-card rationale is real VISIBLE text, not reachable only via a title/hover attribute', () => {
    const attention: AttentionScore = {
      score: 8.0,
      rationale:
        '15 failing · last seen just now · typically 4 min from first sighting to resolve · no self-heal history.',
    }
    const { container } = render(<AttentionBadge attention={attention} />)
    // `container.textContent` never includes attribute values (DOM semantics) — a naive
    // `innerHTML.includes(...)` check would still pass on a hover-only `title="..."`
    // regression, which is exactly the bug this test exists to catch. Only textContent proves
    // the sentence renders as content a non-hovering reader actually sees.
    expect(container.textContent).toContain(
      '15 failing · last seen just now · typically 4 min from first sighting to resolve · no self-heal history.',
    )
  })

  it('#374: the visible rationale is distinct from the fixed glossary sentence, which stays hover-only', () => {
    const attention: AttentionScore = {
      score: 8.0,
      rationale:
        '15 failing · typically 4 min from first sighting to resolve · no self-heal history.',
    }
    render(<AttentionBadge attention={attention} />)
    const badge = screen.getByText('ranked by attention')
    // The generic mechanism explainer (constant, not per-card evidence) still lives in `title`
    // only — duplicating a second, longer, generic sentence into visible text on every card
    // is exactly the "turn every card into a paragraph" outcome §4.3/§12 forbid.
    expect(badge.getAttribute('title')).toMatch(/expected cost of waiting/)
    expect(badge.getAttribute('title')).not.toContain(
      'typically 4 min from first sighting to resolve',
    )
  })
})
