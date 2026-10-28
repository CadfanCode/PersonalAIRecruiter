import { useState } from 'react'
import { Button, TextArea } from '../Form'
import { Panel } from '../Panel'

/** A MANUAL run that could not read the page itself: the user pastes the ad text copied from their own browser. */
export function PasteAdPanel({ url, message, sending, error, onSubmit }: {
  url: string | null | undefined
  message: string | null
  sending: boolean
  error: string | null
  onSubmit: (text: string) => void
}) {
  const [text, setText] = useState('')
  return (
    <Panel title="Paste the ad text">
      <p className="mb-2 text-sm">{message}</p>
      {url && (
        <p className="mb-3 text-sm text-[var(--muted)]">
          Open <a className="underline" href={url} target="_blank" rel="noreferrer noopener">the ad</a> in your own browser, select the whole ad, copy and paste it here.
        </p>
      )}
      <TextArea aria-label="Ad text" rows={10} value={text} onChange={(e) => setText(e.target.value)} placeholder="Paste the ad text here" />
      <div className="mt-3 flex items-center gap-3">
        <Button onClick={() => onSubmit(text)} disabled={text.trim().length < 200 || sending}>{sending ? 'Sending...' : 'Use this text'}</Button>
        {text.trim().length > 0 && text.trim().length < 200 && <span className="text-sm text-[var(--muted)]">A job ad is longer than this.</span>}
      </div>
      {error && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{error}</p>}
    </Panel>
  )
}
