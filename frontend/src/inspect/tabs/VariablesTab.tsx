import { PendingContract } from '../PendingContract'

/**
 * The typed variable ledger tab (SPEC §4, R-UXQ-13). The ledger itself — plain-language
 * type chips, explicit nulls, scope groups, size-capped expansion — is implemented and
 * tested in ../variables (ledger.ts + VariableLedger.tsx). What is missing is the data:
 * the BFF exposes no variables read endpoint yet. When it lands, this tab maps the
 * generated DTO into VariableEntry[] and renders <VariableLedger groups={buildLedger(…)}/>
 * — raw JSON stays the per-row escape hatch, never the presentation.
 */
export default function VariablesTab() {
  return (
    <PendingContract
      promise="Typed variable ledger: name · type chip · value preview · scope · last-modified, case-scope group open by default, step-local variables grouped per execution node."
      endpoint="GET /api/instances/{engineId}/{instanceId}/variables"
    />
  )
}
