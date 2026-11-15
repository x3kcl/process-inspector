// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DiagramToolbar } from './DiagramToolbar'

afterEach(cleanup)

describe('DiagramToolbar', () => {
  it('wires each named button to its handler', () => {
    const handlers = {
      onFit: vi.fn(),
      onZoomIn: vi.fn(),
      onZoomOut: vi.fn(),
      onFullscreen: vi.fn(),
    }
    render(<DiagramToolbar {...handlers} fullscreen={false} />)
    fireEvent.click(screen.getByRole('button', { name: 'Fit' }))
    fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
    fireEvent.click(screen.getByRole('button', { name: 'Zoom out' }))
    fireEvent.click(screen.getByRole('button', { name: 'Full screen' }))
    expect(handlers.onFit).toHaveBeenCalledTimes(1)
    expect(handlers.onZoomIn).toHaveBeenCalledTimes(1)
    expect(handlers.onZoomOut).toHaveBeenCalledTimes(1)
    expect(handlers.onFullscreen).toHaveBeenCalledTimes(1)
  })

  it('hides full screen where the API is absent and names the exit while in it', () => {
    const noop = () => undefined
    const { rerender } = render(
      <DiagramToolbar onFit={noop} onZoomIn={noop} onZoomOut={noop} fullscreen={false} />,
    )
    expect(screen.queryByRole('button', { name: /full screen/i })).toBeNull()
    rerender(
      <DiagramToolbar
        onFit={noop}
        onZoomIn={noop}
        onZoomOut={noop}
        onFullscreen={noop}
        fullscreen
      />,
    )
    expect(screen.getByRole('button', { name: 'Exit full screen' }).textContent).toBe(
      'Exit full screen',
    )
  })
})
