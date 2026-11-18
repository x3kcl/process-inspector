// Pure helpers for the definition Heat tab (#785). STUB — RED commit.
import type { components } from '../../api/schema'
import type { GlyphTexts } from '../../inspect/diagram/markerKinds'

export type Heat = components['schemas']['DefinitionHeat']
export type NodeHeat = components['schemas']['NodeHeat']

export const HEAT_PRESETS: readonly number[] = []

export function badgeText(_node: NodeHeat): string {
  return ''
}

export interface HeatMarkers {
  sets: Record<string, string[]>
  texts: GlyphTexts
}

export function heatMarkers(_nodes: NodeHeat[]): HeatMarkers {
  return { sets: {}, texts: {} }
}

export function honestyLines(_heat: Heat): string[] {
  return []
}

export function refusalMessage(_error: unknown): string {
  return ''
}
