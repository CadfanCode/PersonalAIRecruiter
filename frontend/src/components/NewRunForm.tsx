import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useStartRun } from '../api/runs'
import { Button, Field, TextArea, TextInput } from './Form'
import { Panel } from './Panel'

const EXAMPLES = ['New-grad Java, Stockholm, 7 days', 'PE teacher, Nacka', 'Logistics, Uppsala, part time']

export function NewRunForm() {
  const [prompt, setPrompt] = useState('')
  const [maxAgeDays, setMaxAgeDays] = useState('')
  const [limit, setLimit] = useState('')
  const [includeWeb, setIncludeWeb] = useState(false)
  const [review, setReview] = useState(true)
  const start = useStartRun()
  const navigate = useNavigate()

  const submit = () =>
    start.mutate(
      {
        prompt,
        ...(maxAgeDays ? { maxAgeDays: Number(maxAgeDays) } : {}),
        ...(limit ? { limit: Number(limit) } : {}),
        includeWeb,
        autoContinue: !review,
      },
      { onSuccess: ({ id }) => navigate(`/runs/${id}`) },
    )

  return (
    <Panel title="New run">
      <TextArea
        aria-label="Starting prompt"
        rows={3}
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        placeholder="Describe the roles you want, for example: new-grad Java roles in Stockholm posted in the last 7 days"
      />
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {EXAMPLES.map((text) => (
          <button key={text} type="button" onClick={() => setPrompt(text)}
            className="rounded-full border border-[var(--line)] px-3 py-1 text-xs text-[var(--muted)] hover:border-[var(--accent)] hover:text-[var(--ink)]">
            {text}
          </button>
        ))}
      </div>
      <div className="mt-4 flex flex-wrap items-end gap-4">
        <Field label="Max age (days)">
          <TextInput type="number" min={1} max={90} className="w-24" placeholder="from prompt" value={maxAgeDays} onChange={(e) => setMaxAgeDays(e.target.value)} />
        </Field>
        <Field label="Ads to score">
          <TextInput type="number" min={1} max={200} className="w-24" placeholder="50" value={limit} onChange={(e) => setLimit(e.target.value)} />
        </Field>
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input type="checkbox" checked={review} onChange={(e) => setReview(e.target.checked)} />
          Review the search profile first
        </label>
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input type="checkbox" checked={includeWeb} onChange={(e) => setIncludeWeb(e.target.checked)} />
          Also search career pages (slower)
        </label>
        <Button onClick={submit} disabled={!prompt.trim() || start.isPending}>
          {start.isPending ? 'Starting...' : 'Start run'}
        </Button>
      </div>
      {start.isError && <p role="alert" className="mt-3 text-sm text-red-600">{start.error.message}</p>}
    </Panel>
  )
}
