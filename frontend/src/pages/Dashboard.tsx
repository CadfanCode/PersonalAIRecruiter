import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useCountry } from '../api/country'
import { type SearchModeChoice, useChooseMode, useOnboarding } from '../api/onboarding'
import { useRuns } from '../api/runs'
import { DiscoverForm } from '../components/runs/DiscoverForm'
import { PasteLinkPanel } from '../components/runs/PasteLinkPanel'
import { PromptForm } from '../components/runs/PromptForm'
import { PageTitle, Panel } from '../components/Panel'
import { ReminderBanner } from '../components/ReminderBanner'
import { Pill } from '../components/ui/Pill'
import { MODE_CARDS } from './onboarding/ModeStep'

/** Stage 1, Search mode: Discovery or Prompt, then start. Returning users land here with the mode they chose last. */
export function Dashboard() {
  const onboarding = useOnboarding()
  const country = useCountry()
  const choose = useChooseMode()
  const runs = useRuns()
  const [picked, setPicked] = useState<SearchModeChoice | null>(null)
  const mode: SearchModeChoice = picked ?? onboarding.data?.mode.mode ?? 'PROMPT'

  const pick = (m: SearchModeChoice) => {
    setPicked(m)
    choose.mutate(m)
  }

  return (
    <>
      <PageTitle subtitle="Pick how you want to look for work. You can change it any time.">Find your next role</PageTitle>
      {country.data?.activityReport && <ReminderBanner />}

      <div role="radiogroup" aria-label="Search mode" className="mb-6 grid gap-4 sm:grid-cols-2">
        {MODE_CARDS.map(({ id, title, text, icon: Icon }) => (
          <button key={id} type="button" role="radio" aria-checked={mode === id} onClick={() => pick(id)}
            className={`flex items-start gap-4 rounded-3xl border-2 p-5 text-left transition ${
              mode === id ? 'border-[var(--accent)] bg-[var(--accent-soft)] shadow-[var(--shadow)]' : 'border-[var(--line)] bg-[var(--panel)] hover:border-[var(--muted)]/50'}`}>
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-[var(--panel)] text-[var(--accent)] shadow-sm"><Icon /></span>
            <span>
              <span className="block text-[17px] font-semibold tracking-tight">{title}</span>
              <span className="mt-1 block text-sm leading-relaxed text-[var(--muted)]">{text}</span>
            </span>
          </button>
        ))}
      </div>

      <Panel>
        {mode === 'DISCOVER' ? <DiscoverForm /> : <PromptForm />}
      </Panel>

      <details className="mt-6 rounded-2xl border border-[var(--line)] bg-[var(--panel)] px-5 py-4">
        <summary className="cursor-pointer text-[15px] font-medium">I found a job myself, add it from a link</summary>
        <div className="mt-4"><PasteLinkPanel /></div>
      </details>

      {runs.data && runs.data.length > 0 && (
        <section aria-label="Recent searches" className="mt-10">
          <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-[var(--muted)]">Recent searches</h2>
          <ul className="grid gap-2">
            {runs.data.slice(0, 5).map((r) => (
              <li key={r.id}>
                <Link to={`/runs/${r.id}`} className="flex items-center gap-3 rounded-2xl border border-[var(--line)] bg-[var(--panel)] px-4 py-3 text-sm transition hover:border-[var(--accent)]">
                  <span className="min-w-0 flex-1 truncate font-medium">
                    {r.options.mode === 'DISCOVER' ? 'Discovered roles' : r.options.mode === 'MANUAL' ? 'Pasted link' : (r.prompt ?? 'Search')}
                  </span>
                  <Pill tone={r.status === 'DONE' ? 'success' : r.status === 'FAILED' ? 'danger' : r.status === 'WAITING_FOR_USER' ? 'warn' : 'accent'}>
                    {r.status === 'WAITING_FOR_USER' ? 'waiting for you' : r.status.replace('_', ' ').toLowerCase()}
                  </Pill>
                  <span className="hidden text-xs text-[var(--muted)] sm:inline">{new Date(r.startedAt).toLocaleDateString('sv-SE')}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}
