import { useState } from 'react'
import { useCountry } from '../api/country'
import { type SearchModeChoice, useChooseMode, useOnboarding } from '../api/onboarding'
import { DiscoverForm } from '../components/runs/DiscoverForm'
import { PasteLinkPanel } from '../components/runs/PasteLinkPanel'
import { PromptForm } from '../components/runs/PromptForm'
import { PageTitle, Panel } from '../components/Panel'
import { ReminderBanner } from '../components/ReminderBanner'
import { MODE_CARDS } from './onboarding/ModeStep'

/** Stage 1, Mode: Discovery or Prompt, then start. Returning users land here with the mode they chose last. */
export function Dashboard() {
  const onboarding = useOnboarding()
  const country = useCountry()
  const choose = useChooseMode()
  const [picked, setPicked] = useState<SearchModeChoice | null>(null)
  const mode: SearchModeChoice = picked ?? onboarding.data?.mode.mode ?? 'PROMPT'

  const pick = (m: SearchModeChoice) => {
    setPicked(m)
    choose.mutate(m)
  }

  return (
    <>
      <PageTitle>Find your next role</PageTitle>
      {country.data?.activityReport && <ReminderBanner />}

      <div role="radiogroup" aria-label="Mode" className="mb-6 grid gap-4 sm:grid-cols-2">
        {MODE_CARDS.map(({ id, title, icon: Icon }) => (
          <button key={id} type="button" role="radio" aria-checked={mode === id} onClick={() => pick(id)}
            className={`lift flex items-center gap-4 rounded-2xl border p-5 text-left ${
              mode === id ? 'border-[var(--accent)] bg-[var(--accent-wash)] shadow-[var(--shadow)]' : 'border-[var(--line)] bg-[var(--panel)] hover:border-[var(--muted)]/50'}`}>
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-[var(--panel)] text-[var(--accent)] shadow-sm"><Icon /></span>
            <span className="self-center text-[17px] font-semibold tracking-tight">{title}</span>
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
    </>
  )
}
