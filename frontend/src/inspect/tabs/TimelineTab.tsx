import { PendingContract } from '../PendingContract'

export default function TimelineTab() {
  return (
    <PendingContract
      promise="Historic activity instances as duration bars with call-activity sub-lanes; the failing activity annotated with live job state."
      endpoint="GET /api/instances/{engineId}/{instanceId}/timeline"
    />
  )
}
