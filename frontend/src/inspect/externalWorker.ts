import type { EngineDto } from '../api/model'

/**
 * The External Worker lane (v1.x #7) renders ONLY on a capability-supporting engine
 * (Flowable ≥ 6.8, `EngineDto.capabilities.externalWorkerJobs`). Kept pure so the graceful-
 * degradation contract — no empty lane, no spinner on a pre-6.8 engine — is unit-testable
 * under the node-env vitest. `undefined` (engine/caps not loaded) reads as hidden: stay dark
 * until the capability is known, never flash a lane we might have to hide.
 */
export function externalWorkerLaneVisible(engine: EngineDto | undefined): boolean {
  return engine?.capabilities?.externalWorkerJobs === true
}
