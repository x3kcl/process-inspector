import { CopyButton } from '../components/CopyButton'
import { saveTextAs } from '../ops/exportCsv'

/**
 * Per-tab raw-JSON escape hatch (R-L3-03, SPEC §4): the support-bundle kernel. The typed view stays
 * the DEFAULT presentation (§4a — never a raw dump), but every detail tab offers a download (and a
 * copy) of the full DTO behind the rendered view, so an operator can attach it to a support ticket
 * without a client hack. Pure client-side over the already-fetched, already-authorized tab data —
 * no new endpoint (verbatim engine passthrough would be R-L3-04, out of scope). Renders nothing
 * until the tab's data has loaded.
 */
export function RawJsonExport({ data, filename }: { data: unknown; filename: string }) {
  if (data === undefined || data === null) return null
  const json = stringifyRaw(data)
  return (
    <div className="raw-json-export">
      <button
        type="button"
        className="copy-btn"
        title="Download the raw JSON behind this view (support bundle)"
        onClick={() => {
          saveTextAs(json, filename, 'application/json')
        }}
      >
        ⬇ raw JSON
      </button>
      <CopyButton text={json} label="copy raw JSON" />
    </div>
  )
}

function stringifyRaw(data: unknown): string {
  try {
    return JSON.stringify(data, null, 2)
  } catch {
    // Circular refs must never crash the export — fall back to a best-effort string.
    return String(data)
  }
}
