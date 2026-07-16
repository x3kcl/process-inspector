// @vitest-environment jsdom
// Issue #213: a caller next to a gated action (e.g. ErrorsJobsTab's tier-0 retry) can pass
// that SAME gate so the toggle greys with a reason instead of staying clickable and only
// failing once opened, with a bare "Could not render the cURL: Forbidden".
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CurlPreview } from './CurlPreview'

afterEach(cleanup)

function renderPreview(gate?: { enabled: boolean; reason?: string; detail?: string }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const fetchCurl = vi.fn().mockResolvedValue({ curl: 'curl -X POST ...' })
  render(
    <QueryClientProvider client={client}>
      <CurlPreview queryKey={['test']} fetchCurl={fetchCurl} gate={gate} />
    </QueryClientProvider>,
  )
  return { fetchCurl }
}

describe('CurlPreview — optional gate (#213)', () => {
  it('stays fully unlocked when no gate is passed (existing modal-based callers)', () => {
    renderPreview()
    const toggle = screen.getByRole('button', { name: 'Show as cURL' })
    expect(toggle.hasAttribute('disabled')).toBe(false)
  })

  it('stays unlocked when the gate says enabled', () => {
    renderPreview({ enabled: true })
    const toggle = screen.getByRole('button', { name: 'Show as cURL' })
    expect(toggle.hasAttribute('disabled')).toBe(false)
  })

  it('greys with a visible reason when the gate says disabled — same convention as InlineConfirm', () => {
    renderPreview({ enabled: false, reason: 'Requires RESPONDER — you are VIEWER' })
    const toggle = screen.getByRole('button', { name: 'Show as cURL' })
    expect(toggle.hasAttribute('disabled')).toBe(true)
    expect(screen.getByText(/Requires RESPONDER/)).toBeTruthy()
  })

  it('never fetches the cURL when locked, even if clicked', () => {
    const { fetchCurl } = renderPreview({ enabled: false, reason: 'blocked' })
    const toggle = screen.getByRole('button', { name: 'Show as cURL' })
    fireEvent.click(toggle)
    expect(fetchCurl).not.toHaveBeenCalled()
  })
})
