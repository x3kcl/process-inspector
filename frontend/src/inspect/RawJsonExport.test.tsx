// @vitest-environment jsdom
// R-L3-03 (#118): the per-tab support-bundle export — download (and copy) the raw JSON behind the
// rendered view. Nothing renders until the tab data has loaded.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RawJsonExport } from './RawJsonExport'

const saveTextAs = vi.fn()
vi.mock('../ops/exportCsv', () => ({
  saveTextAs: (...args: unknown[]) => saveTextAs(...args) as unknown,
}))

afterEach(() => {
  cleanup()
  saveTextAs.mockClear()
})

describe('RawJsonExport (R-L3-03)', () => {
  it('renders nothing until the tab data has loaded', () => {
    const { container } = render(<RawJsonExport data={undefined} filename="x.json" />)
    expect(container.firstChild).toBeNull()
  })

  it('downloads the pretty-printed DTO as application/json under the tab filename', () => {
    const data = { tasks: [{ id: 't-1', state: 'ACTIVE' }], total: 1 }
    render(<RawJsonExport data={data} filename="engine-a-pi-1-tasks.json" />)

    fireEvent.click(screen.getByTitle(/download the raw json/i))

    expect(saveTextAs).toHaveBeenCalledTimes(1)
    const [text, filename, mediaType] = saveTextAs.mock.calls[0] as [string, string, string]
    expect(filename).toBe('engine-a-pi-1-tasks.json')
    expect(mediaType).toBe('application/json')
    expect(text).toBe(JSON.stringify(data, null, 2)) // the raw view behind the render, pretty-printed
    expect(JSON.parse(text)).toEqual(data) // and it round-trips
  })
})
