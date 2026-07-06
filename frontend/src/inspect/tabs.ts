// Stage 2 tab registry (SPEC §4): deep links are tab-aware — ?tab=timeline is the
// ticket-handover primitive, so the tab set is a typed URL contract, not component state.
export const TAB_IDS = [
  'variables',
  'errors-jobs',
  'tasks',
  'hierarchy',
  'timeline',
  'audit',
] as const

export type TabId = (typeof TAB_IDS)[number]

export const TAB_LABELS: Record<TabId, string> = {
  variables: 'Variables',
  'errors-jobs': 'Errors & Jobs',
  tasks: 'Tasks',
  hierarchy: 'Hierarchy',
  timeline: 'Timeline',
  audit: 'Audit & Notes',
}

export const DEFAULT_TAB: TabId = 'variables'

export function isTabId(value: string): value is TabId {
  return (TAB_IDS as readonly string[]).includes(value)
}
