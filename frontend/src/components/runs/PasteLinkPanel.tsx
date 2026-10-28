import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useImportJob } from '../../api/runs'
import { Button, Field, TextArea, TextInput } from '../Form'

/**
 * "I found a job myself": a link, and for sites that discourage bots (LinkedIn) the ad text pasted from the user's own browser.
 * The job then goes through the same pipeline as every other.
 */
export function PasteLinkPanel() {
  const [url, setUrl] = useState('')
  const [adText, setAdText] = useState('')
  const importJob = useImportJob()
  const navigate = useNavigate()
  const add = () =>
    importJob.mutate({ url: url.trim(), ...(adText.trim() ? { text: adText.trim() } : {}) }, { onSuccess: ({ id }) => navigate(`/runs/${id}`) })

  return (
    <div>
      <Field label="Address of the job ad">
        <TextInput aria-label="Job ad address" type="url" placeholder="https://…" value={url} onChange={(e) => setUrl(e.target.value)} />
      </Field>
      <details className="mt-3">
        <summary className="cursor-pointer text-sm text-[var(--muted)]">I already have the ad text (needed for LinkedIn)</summary>
        <TextArea aria-label="Ad text" rows={6} className="mt-2" value={adText} onChange={(e) => setAdText(e.target.value)}
          placeholder="Copy the whole ad from your own browser and paste it here" />
      </details>
      <p className="mt-3 text-xs leading-relaxed text-[var(--muted)]">
        Sites that discourage bots, such as LinkedIn, are never opened or read by JobPilot: paste the ad text and apply there yourself.
        Other sites are read once, only to get the ad.
      </p>
      <div className="mt-4">
        <Button onClick={add} disabled={!url.trim() || importJob.isPending}>{importJob.isPending ? 'Adding…' : 'Add this job'}</Button>
      </div>
      {importJob.isError && <p role="alert" className="mt-3 text-sm text-[var(--danger)]">{importJob.error.message}</p>}
    </div>
  )
}
