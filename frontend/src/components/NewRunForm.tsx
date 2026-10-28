import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { DISCOVER_AGGRESSIVE_LIMIT, DISCOVER_LIMIT, DISCOVER_MAX_LIMIT, type RunMode, useImportJob, useStartRun } from '../api/runs'
import { Button, Field, TextArea, TextInput } from './Form'
import { Panel } from './Panel'

const EXAMPLES = ['Nurse positions in Stockholm, last 7 days', 'Accountant, Malmö, part time', 'Electrician, Uppsala, this week']
const DISCOVER_EXAMPLES = ['Stockholm region, full time', 'Uppsala or Stockholm, 14 days', 'Anywhere in Sweden, remote is fine']

const MODES: { key: RunMode; label: string; hint: string }[] = [
  { key: 'TARGETED', label: 'Targeted search', hint: 'You name the role; JobPilot finds and ranks the ads.' },
  { key: 'DISCOVER', label: 'Discover roles', hint: 'JobPilot reads your whole CV and suggests roles you would be strong for, including ones you have not thought of.' },
  { key: 'MANUAL', label: 'Paste a job link', hint: 'You found a job yourself, for example on LinkedIn; JobPilot handles it like any other.' },
]

export function NewRunForm() {
  const [mode, setMode] = useState<RunMode>('TARGETED')
  const [prompt, setPrompt] = useState('')
  const [maxAgeDays, setMaxAgeDays] = useState('')
  const [limit, setLimit] = useState('')
  const [includeWeb, setIncludeWeb] = useState(false)
  const [review, setReview] = useState(true)
  const [aggressive, setAggressive] = useState(false)
  const start = useStartRun()
  const navigate = useNavigate()
  const discover = mode === 'DISCOVER'
  const manual = mode === 'MANUAL'
  const [url, setUrl] = useState('')
  const [adText, setAdText] = useState('')
  const importJob = useImportJob()
  const addJob = () =>
    importJob.mutate({ url: url.trim(), ...(adText.trim() ? { text: adText.trim() } : {}) }, { onSuccess: ({ id }) => navigate(`/runs/${id}`) })

  const submit = () =>
    start.mutate(
      {
        prompt,
        mode,
        ...(maxAgeDays ? { maxAgeDays: Number(maxAgeDays) } : {}),
        ...(limit ? { limit: Number(limit) } : {}),
        ...(discover ? { aggressive } : { includeWeb, autoContinue: !review }),
      },
      { onSuccess: ({ id }) => navigate(`/runs/${id}`) },
    )

  return (
    <Panel title="New run">
      <div role="radiogroup" aria-label="Search mode" className="mb-4 grid grid-cols-3 gap-2">
        {MODES.map((m) => (
          <label key={m.key}
            className={`cursor-pointer rounded-md border p-3 ${mode === m.key ? 'border-[var(--accent)] bg-navy/5' : 'border-[var(--line)]'}`}>
            <span className="flex items-center gap-2 text-sm font-medium">
              <input type="radio" name="mode" checked={mode === m.key} onChange={() => setMode(m.key)} />
              {m.label}
            </span>
            <span className="mt-1 block text-xs text-[var(--muted)]">{m.hint}</span>
          </label>
        ))}
      </div>

      {manual ? (
        <div>
          <Field label="Address of the job ad">
            <TextInput aria-label="Job ad address" type="url" placeholder="https://www.linkedin.com/jobs/view/..." value={url} onChange={(e) => setUrl(e.target.value)} />
          </Field>
          <details className="mt-3">
            <summary className="cursor-pointer text-sm text-[var(--muted)]">I already have the ad text (needed for LinkedIn)</summary>
            <TextArea aria-label="Ad text" rows={6} className="mt-2" value={adText} onChange={(e) => setAdText(e.target.value)}
              placeholder="Copy the whole ad from your own browser and paste it here" />
          </details>
          <p className="mt-3 text-xs text-[var(--muted)]">
            Sites that discourage bots, such as LinkedIn, are never opened or read by JobPilot: paste the ad text and apply there yourself.
            Other sites are read once, only to get the ad.
          </p>
          <div className="mt-4">
            <Button onClick={addJob} disabled={!url.trim() || importJob.isPending}>{importJob.isPending ? 'Adding...' : 'Add this job'}</Button>
          </div>
          {importJob.isError && <p role="alert" className="mt-3 text-sm text-red-600">{importJob.error.message}</p>}
        </div>
      ) : (
      <>
      <TextArea
        aria-label={discover ? 'Where and how you want to work' : 'Starting prompt'}
        rows={3}
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        placeholder={discover
          ? 'Optional: where and how you want to work, for example: Stockholm region, full time, ads from the last 14 days'
          : 'Describe the roles you want, for example: nurse positions in Stockholm posted in the last 7 days'}
      />
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {(discover ? DISCOVER_EXAMPLES : EXAMPLES).map((text) => (
          <button key={text} type="button" onClick={() => setPrompt(text)}
            className="rounded-full border border-[var(--line)] px-3 py-1 text-xs text-[var(--muted)] hover:border-[var(--accent)] hover:text-[var(--ink)]">
            {text}
          </button>
        ))}
      </div>
      <div className="mt-4 flex flex-wrap items-end gap-4">
        <Field label="Max age (days)">
          <TextInput type="number" min={1} max={90} className="w-24" placeholder={discover && aggressive ? '14' : 'from prompt'} value={maxAgeDays} onChange={(e) => setMaxAgeDays(e.target.value)} />
        </Field>
        <Field label={discover ? `Ads to score (max ${DISCOVER_MAX_LIMIT})` : 'Ads to score'}>
          <TextInput type="number" min={1} max={discover ? DISCOVER_MAX_LIMIT : 200} className="w-24"
            placeholder={discover ? String(aggressive ? DISCOVER_AGGRESSIVE_LIMIT : DISCOVER_LIMIT) : '50'} value={limit} onChange={(e) => setLimit(e.target.value)} />
        </Field>
        {discover ? (
          <label className="flex items-center gap-2 pb-2 text-sm" title="More role types, a wider age limit, more ads scored and a lower minimum score">
            <input type="checkbox" checked={aggressive} onChange={(e) => setAggressive(e.target.checked)} />
            Aggressive: more roles, wider search, lower bar
          </label>
        ) : (
          <>
            <label className="flex items-center gap-2 pb-2 text-sm">
              <input type="checkbox" checked={review} onChange={(e) => setReview(e.target.checked)} />
              Review the search profile first
            </label>
            <label className="flex items-center gap-2 pb-2 text-sm">
              <input type="checkbox" checked={includeWeb} onChange={(e) => setIncludeWeb(e.target.checked)} />
              Also search career pages (slower)
            </label>
          </>
        )}
        <Button onClick={submit} disabled={(!discover && !prompt.trim()) || start.isPending}>
          {start.isPending ? 'Starting...' : discover ? 'Find roles' : 'Start run'}
        </Button>
      </div>
      {start.isError && <p role="alert" className="mt-3 text-sm text-red-600">{start.error.message}</p>}
      </>
      )}
    </Panel>
  )
}
