import { useDraft } from '../../lib/useDraft'
import { useNavigate } from 'react-router-dom'
import { DISCOVER_AGGRESSIVE_LIMIT, DISCOVER_LIMIT, DISCOVER_MAX_LIMIT, useStartRun } from '../../api/runs'
import { Button, Field, TextArea, TextInput } from '../Form'

const EXAMPLES = ['Stockholm region, full time', 'Uppsala or Stockholm, last 14 days', 'Anywhere in Sweden, remote is fine']

/** Discovery mode: nothing to configure but, if you like, where and how you want to work. The search profile is made for you. */
export function DiscoverForm() {
  const [context, setContext, clearContext] = useDraft('mode.discover', '')
  const [limit, setLimit, clearLimit] = useDraft('mode.discover.limit', '')
  const [aggressive, setAggressive, clearAggressive] = useDraft('mode.discover.wide', false)
  const start = useStartRun()
  const navigate = useNavigate()
  const submit = () =>
    start.mutate({ prompt: context, mode: 'DISCOVER', aggressive, ...(limit ? { limit: Number(limit) } : {}) }, { onSuccess: ({ id }) => { clearContext(); clearLimit(); clearAggressive(); navigate(`/runs/${id}`) } })

  return (
    <div>
      <Field label="Where and how you want to work (optional)">
        <TextArea aria-label="Where and how you want to work" rows={2} value={context} onChange={(e) => setContext(e.target.value)}
          placeholder="For example: Stockholm region, full time, ads from the last 14 days" className="text-[15px]" />
      </Field>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {EXAMPLES.map((text) => (
          <button key={text} type="button" onClick={() => setContext(text)}
            className="rounded-full border border-[var(--line)] px-3 py-1 text-xs text-[var(--muted)] transition hover:border-[var(--accent)] hover:text-[var(--accent)]">
            {text}
          </button>
        ))}
      </div>
      <div className="mt-6 flex flex-wrap items-end gap-5">
        <label className="flex items-center gap-2 pb-2 text-sm" title="More role types, older ads, more ads scored and a lower minimum score">
          <input type="checkbox" checked={aggressive} onChange={(e) => setAggressive(e.target.checked)} className="h-4 w-4 accent-[var(--accent)]" />
          Go wider: more roles, older ads, a lower bar
        </label>
        <Field label={`Ads to score (up to ${DISCOVER_MAX_LIMIT})`}>
          <TextInput type="number" min={1} max={DISCOVER_MAX_LIMIT} className="w-28" placeholder={String(aggressive ? DISCOVER_AGGRESSIVE_LIMIT : DISCOVER_LIMIT)}
            value={limit} onChange={(e) => setLimit(e.target.value)} />
        </Field>
        <Button size="lg" className="ml-auto" onClick={submit} disabled={start.isPending}>{start.isPending ? 'Starting…' : 'Find roles'}</Button>
      </div>
      {start.isError && <p role="alert" className="mt-3 text-sm text-[var(--danger)]">{start.error.message}</p>}
    </div>
  )
}
