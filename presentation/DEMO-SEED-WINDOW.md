# Demo seed window — presentation footage

Instances created on the live demo purely to record the feature videos.
**Exclude this window from R1/R2 data-maturity gate measurements** (ALARM-COST-MODEL.md §7,
RETRYING-RISK-LANE.md §7.2) and from any audit-log mining of operator behaviour: the
corrective actions inside it were performed by a recorder, not by an operator.

| | |
|---|---|
| opened | `2026-09-14T15:02:30Z` |
| closed | `2026-09-14T15:40:00Z` (last recorded clip) |
| engines | engine-a, engine-b |
| definition | `demoFailingPayment` |
| business keys | `PAY-OK-%` (successful siblings) · `PAY-FIX-%` (surgery targets) |

Actions performed on camera, all by user `operator`:
edit-variable ×2 (divisor 0→2 on PAY-FIX-100 and PAY-FIX-250), retry-job ×2 (both
instances then reached COMPLETED), and three `retry-job` error-class bulk jobs over the
`java.lang.ArithmeticException` class (9 of 9 dispatched each).

Regenerate with `presentation/seed-demo-for-video.sh`.
