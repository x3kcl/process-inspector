interface Props {
  /** What SPEC §4 promises this surface will show. */
  promise: string
  /** The BFF endpoint that has to land first (kept literal — it is the work order). */
  endpoint: string
}

/**
 * The honest placeholder for Stage 2 surfaces whose BFF contract has not landed yet.
 * Never fake data, never render an empty frame that could read as "nothing here" —
 * name the missing endpoint so the gap is a work item, not a mystery (R-SEM-12 spirit).
 */
export function PendingContract({ promise, endpoint }: Props) {
  return (
    <div className="zero-state pending-contract">
      <p>{promise}</p>
      <p>
        Waiting on the BFF detail contract: <code>{endpoint}</code> is not exposed yet. This tab
        binds automatically once the endpoint lands and <code>npm run gen:api</code> regenerates the
        schema.
      </p>
    </div>
  )
}
