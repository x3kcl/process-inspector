import { describe, expect, it } from 'vitest'
import { MARKER_KINDS } from './markerKinds'

// #677 review: the change-state picker paints the completed ✓ and the picker S/T glyphs on
// the same node (a rerun target is usually a completed step). Both sit top-left, so the ✓
// must start past the 16 px picker glyph instead of covering it.
const glyphOf = (key: string) => MARKER_KINDS.find((kind) => kind.key === key)?.glyph?.position

describe('marker glyph placement', () => {
  it.each(['pickerSourceIds', 'pickerTargetIds'])(
    'the completed ✓ does not cover the %s glyph',
    (pickerKey) => {
      const picker = glyphOf(pickerKey)
      const completed = glyphOf('completedActivityIds')
      expect(picker?.left).toBeDefined()
      expect(completed?.left).toBeDefined()
      expect(completed?.left ?? 0).toBeGreaterThanOrEqual((picker?.left ?? 0) + 16)
    },
  )
})
