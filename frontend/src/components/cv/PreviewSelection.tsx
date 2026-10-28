import { useState } from 'react'
import { usePreviewSelection } from '../../api/settings'
import { Button, TextArea } from '../Form'
import { Panel } from '../Panel'

/** "Preview selection for..." (SPEC 12.6): paste an ad and see which CV facts would be chosen. Nothing is saved. */
export function PreviewSelection() {
  const [ad, setAd] = useState('')
  const preview = usePreviewSelection()
  return (
    <Panel title="Preview selection for an ad">
      <p className="mb-3 text-sm text-[var(--muted)]">
        Paste a job ad to see which entries and facts Claude would pick, using your newest search profile. Nothing is stored.
      </p>
      <TextArea aria-label="Pasted ad" rows={8} value={ad} onChange={(e) => setAd(e.target.value)} placeholder="Paste the ad text here" />
      <div className="mt-3 flex items-center gap-3">
        <Button disabled={!ad.trim() || preview.isPending} onClick={() => preview.mutate(ad)}>
          {preview.isPending ? 'Choosing...' : 'Preview selection'}
        </Button>
        {preview.isPending && <span className="text-sm text-[var(--muted)]">This calls Claude once.</span>}
      </div>
      {preview.isError && <p role="alert" className="mt-3 text-sm text-[var(--danger)]">{preview.error.message}</p>}
      {preview.data && (
        <div className="mt-4">
          <p className="mb-2 text-xs text-[var(--muted)]">Ad language: {preview.data.language === 'sv' ? 'Swedish' : 'English'}</p>
          <ul className="flex flex-col gap-3">
            {preview.data.entries.map((e) => (
              <li key={e.entryId} className="rounded-md border border-[var(--line)] p-3">
                <p className="font-medium">
                  {e.title} <span className="text-xs font-normal text-[var(--muted)]">({e.kind.toLowerCase()}, used in {e.use})</span>
                </p>
                <p className="text-xs text-[var(--muted)]">{e.reason}</p>
                <ul className="mt-1 list-disc pl-5 text-sm">
                  {e.facts.map((f) => <li key={f.id}>{f.text}</li>)}
                </ul>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Panel>
  )
}
