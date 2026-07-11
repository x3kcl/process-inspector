// R-AUD-09 (SPEC §9 attribution tradeoff, usability W3-1): the BFF calls engines with a
// shared service account, so Flowable's OWN history tables blame every mutation on that
// account. The baseline run's M7 tester attached their attribution doubt to fixture noise
// instead of this trap — exactly the un-warned user the requirement predicts. The caveat
// is static, info-tone, and rides EVERY audit read surface (instance tab + ops log).
import { ActionHint } from './ActionHint'

export const ATTRIBUTION_CAVEAT =
  'Engine-side history attributes these actions to the shared service account — ' +
  'this log is the authoritative WHO.'

export function AttributionCaveat() {
  return <ActionHint tone="info" text={ATTRIBUTION_CAVEAT} />
}
