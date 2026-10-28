import { useDraft } from '../../lib/useDraft'
import { useNavigate } from 'react-router-dom'
import { DISCOVER_MAX_LIMIT, PROMPT_LIMIT, useStartRun } from '../../api/runs'
import { Button, Field, TextArea, TextInput } from '../Form'

const EXAMPLES = [
  'Nurse positions in Stockholm, last 7 days',
  'Accountant in Malmö, part time',
  'Electrician in Uppsala, this week',
  'Primary school teacher near Göteborg',
  'Warehouse operator, Örebro, full time',
  'Junior Java developer in Stockholm, last 14 days',
]

const TIPS = [
  'Name the job (or two), the place, and how recent the ads should be: "last 7 days" is understood.',
  'Say what matters: full time or part time, remote is fine, junior or senior.',
  'You will see the search plan before anything is searched, and can change it.',
]

/** Prompt mode: describe the roles in plain words. Recency and place are read from the words, not from separate fields. */
export function PromptForm() {
  const [prompt, setPrompt, clearPrompt] = useDraft('mode.prompt', '')
  const [limit, setLimit, clearLimit] = useDraft('mode.prompt.limit', '')
  const start = useStartRun()
  const navigate = useNavigate()
  const submit = () =>
    start.mutate({ prompt, mode: 'TARGETED', ...(limit ? { limit: Number(limit) } : {}) }, { onSuccess: ({ id }) => { clearPrompt(); clearLimit(); navigate(`/runs/${id}`) } })

  return (
    <div>
      <TextArea aria-label="Starting prompt" rows={4} value={prompt} onChange={(e) => setPrompt(e.target.value)}
        placeholder="Describe the roles you want, for example: nurse positions in Stockholm posted in the last 7 days" className="text-[15px]" />
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {EXAMPLES.map((text) => (
          <button key={text} type="button" onClick={() => setPrompt(text)}
            className="rounded-full border border-[var(--line)] px-3 py-1 text-xs text-[var(--muted)] transition hover:border-[var(--accent)] hover:text-[var(--accent)]">
            {text}
          </button>
        ))}
      </div>
      <ul className="mt-5 flex flex-col gap-1.5 text-sm text-[var(--muted)]">
        {TIPS.map((t) => <li key={t} className="flex gap-2"><span aria-hidden className="text-[var(--accent)]">•</span>{t}</li>)}
      </ul>
      <div className="mt-6 flex flex-wrap items-end gap-4">
        <Field label={`Ads to score (up to ${DISCOVER_MAX_LIMIT})`}>
          <TextInput type="number" min={1} max={DISCOVER_MAX_LIMIT} className="w-28" placeholder={String(PROMPT_LIMIT)} value={limit} onChange={(e) => setLimit(e.target.value)} />
        </Field>
        <Button size="lg" className="ml-auto" onClick={submit} disabled={!prompt.trim() || start.isPending}>
          {start.isPending ? 'Starting…' : 'Make a search plan'}
        </Button>
      </div>
      {start.isError && <p role="alert" className="mt-3 text-sm text-[var(--danger)]">{start.error.message}</p>}
    </div>
  )
}
