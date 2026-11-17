// #743 stub — the audited reveal call.
export interface RevealTarget {
  kind: 'instance' | 'case'
  engineId: string
  targetId: string
}

export function revealVariable(
  _target: RevealTarget,
  _name: string,
  _executionId?: string,
  _reason?: string,
): Promise<{ name?: string; type?: string; value?: unknown }> {
  return Promise.reject(new Error('#743 stub'))
}
