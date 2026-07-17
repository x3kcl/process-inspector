// @vitest-environment jsdom
// #245: the depth-cap note must render whenever depthCapped fired — even over an EMPTY
// accumulated row set (previously the lastStartTime guard blanked the whole message) —
// and must be styled as an act-on-this warning, distinct from the muted staleness note.
import { readFileSync } from 'node:fs'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DEPTH_WALL_HINT, DepthWallNote } from './DepthWallNote'

afterEach(cleanup)

describe('DepthWallNote (#245)', () => {
  it('offers the pre-filled narrowing CTA when rows have loaded', () => {
    const onNarrow = vi.fn()
    render(<DepthWallNote lastStartTime="2026-07-16T14:03:22.000Z" onNarrow={onNarrow} />)

    const note = screen.getByRole('note')
    expect(note.textContent).toContain('Reached the paging depth on at least one engine')
    // SPEC §10a A-copy: short visible line, long explanation behind the title.
    expect(note.getAttribute('title')).toBe(DEPTH_WALL_HINT)

    fireEvent.click(screen.getByRole('button', { name: /continue by narrowing/i }))
    expect(onNarrow).toHaveBeenCalledWith('2026-07-16T14:03:22.000Z')
  })

  it('still renders (fallback copy, no CTA) when NO rows accumulated — never an empty region', () => {
    render(<DepthWallNote lastStartTime={undefined} onNarrow={vi.fn()} />)

    const note = screen.getByRole('note')
    expect(note.textContent).toContain('Reached the paging depth on at least one engine')
    // [why the grid is empty] + [next move] without a fabricated time bound:
    expect(note.textContent).toContain('before any rows could be loaded')
    expect(note.textContent).toMatch(/narrow the search/i)
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('carries the depth-wall class, not the muted seam/staleness classes', () => {
    render(<DepthWallNote lastStartTime={undefined} onNarrow={vi.fn()} />)
    const note = screen.getByRole('note')
    expect(note.className).toBe('load-more-depthwall')
    expect(note.className).not.toContain('load-more-seam')
    expect(note.className).not.toContain('snapshot-stale')
  })

  it('styles .load-more-depthwall as its own warning-toned rule, split from .load-more-seam', () => {
    // vitest stubs CSS imports (even `?raw`) to '' — read the sheet off disk instead
    // (cwd-relative: vitest always runs from frontend/, and jsdom's import.meta.url
    // is http-scheme, so a URL-relative read cannot work here).
    const css = readFileSync('src/styles.css', 'utf8')
    // The old shared muted rule must be gone…
    expect(css).not.toMatch(/\.load-more-seam,\s*\.load-more-depthwall/)
    // …and the standalone rule must carry visual weight beyond hue (border + warning tokens).
    const rule = /^\.load-more-depthwall\s*\{[^}]*\}/m.exec(css)?.[0]
    expect(rule).toBeDefined()
    expect(rule).toContain('--color-warning')
    expect(rule).toContain('border')
  })
})
