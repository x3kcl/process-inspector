// The §4a source-mode editor chunk. This module is loaded ONLY via React.lazy from
// EditorPanel — CodeMirror must never ship in the page bundle (§4a banned list:
// "bundling the source editor eagerly").
import { useEffect, useRef } from 'react'
import { EditorView, basicSetup } from 'codemirror'
import { json } from '@codemirror/lang-json'

interface Props {
  /** Initial buffer — the panel owns the authoritative copy via onChange. */
  initialValue: string
  onChange: (buffer: string) => void
}

export default function SourceEditor({ initialValue, onChange }: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    const host = hostRef.current
    if (host === null) return
    const view = new EditorView({
      doc: initialValue,
      parent: host,
      extensions: [
        basicSetup,
        json(),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) onChangeRef.current(update.state.doc.toString())
        }),
      ],
    })
    return () => {
      view.destroy()
    }
    // The buffer is the operator's working copy once mounted; external re-inits would
    // clobber keystrokes, so the doc is seeded exactly once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="source-editor">
      <div className="source-band">SOURCE — exact typed payload</div>
      <div ref={hostRef} />
    </div>
  )
}
