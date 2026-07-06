import { PendingContract } from '../PendingContract'

/** Four Flowable job lanes kept distinct (SPEC §4): the lane IS the diagnosis. */
export default function ErrorsJobsTab() {
  return (
    <PendingContract
      promise="Errors & Jobs: the four job lanes (executable / timer / suspended / dead-letter) rendered separately, per-job retries, create time, and the stacktrace fetched on expand."
      endpoint="GET /api/instances/{engineId}/{instanceId}/jobs"
    />
  )
}
