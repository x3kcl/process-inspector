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

  it('renders the visible marker and a tooltip carrying the SERVER rationale verbatim', () => {
    const attention: AttentionScore = {
      score: 4.2,
      rationale:
        '21 failing · last seen 2 min ago · typically takes 4 h to resolve · no self-heal history.',
    }
    render(<AttentionBadge attention={attention} />)
    const badge = screen.getByText('ranked by attention')
    expect(badge.getAttribute('title')).toContain(
      '21 failing · last seen 2 min ago · typically takes 4 h to resolve · no self-heal history.',
    )
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
    const badge = screen.getByText('ranked by attention')
    expect(badge.getAttribute('title')).toContain('CUSTOM SERVER SENTENCE — do not recompose me')
  })
})
