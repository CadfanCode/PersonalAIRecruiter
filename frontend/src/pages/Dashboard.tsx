import { useCountry } from '../api/country'
import { type SearchModeChoice, useChooseMode } from '../api/onboarding'
import { useDraft } from '../lib/useDraft'
import { DiscoverForm } from '../components/runs/DiscoverForm'
import { PasteLinkPanel } from '../components/runs/PasteLinkPanel'
import { PromptForm } from '../components/runs/PromptForm'
import { PageTitle, Panel } from '../components/Panel'
import { ReminderBanner } from '../components/ReminderBanner'
import { MODE_CARDS } from './onboarding/ModeStep'

/** Stage 1, Mode: two large buttons; choosing Discovery or Prompt shrinks them and opens the matching form below. */
export function Dashboard() {
  const country = useCountry()
  const choose = useChooseMode()
  // Nothing is chosen when the screen opens, whatever was chosen last time: the user picks a mode to see its prompt.
  const [mode, setMode] = useDraft<SearchModeChoice | null>('mode.picked', null)

  const pick = (m: SearchModeChoice) => {
    setMode(m)
    choose.mutate(m)
  }
  const chosen = mode !== null

  return (
    <>
      <PageTitle>Find your next role</PageTitle>
      {country.data?.activityReport && <ReminderBanner />}

      <div role="radiogroup" aria-label="Mode" className={`grid gap-4 transition-[margin] duration-500 ease-out motion-reduce:transition-none sm:grid-cols-2 ${chosen ? 'mb-6' : 'mb-8'}`}>
        {MODE_CARDS.map(({ id, title, icon: Icon }) => (
          <button key={id} type="button" role="radio" aria-checked={mode === id} onClick={() => pick(id)}
            className={`lift flex items-center rounded-2xl border text-left transition-all duration-500 ease-out motion-reduce:transition-none ${
              chosen ? 'min-h-[5.5rem] gap-4 p-5' : 'min-h-[13rem] flex-col justify-center gap-5 p-10 text-center sm:min-h-[16rem]'} ${
              mode === id ? 'border-[var(--accent)] bg-[var(--accent-wash)] shadow-[var(--shadow)]' : 'border-[var(--line)] bg-[var(--panel)] hover:border-[var(--muted)]/50'}`}>
            <span className={`flex shrink-0 items-center justify-center rounded-full bg-[var(--panel)] text-[var(--accent)] shadow-sm transition-all duration-500 ease-out motion-reduce:transition-none ${
              chosen ? 'h-11 w-11 scale-100' : 'h-20 w-20 scale-110'}`}><Icon /></span>
            <span className={`font-semibold tracking-tight transition-all duration-500 ease-out motion-reduce:transition-none ${chosen ? 'self-center text-[17px]' : 'text-2xl'}`}>{title}</span>
          </button>
        ))}
      </div>

      {/* The prompt opens under the cards once a mode is chosen: the row grows from 0 to its natural height. */}
      <div aria-hidden={!chosen} inert={!chosen}
        className={`grid transition-[grid-template-rows,opacity] duration-500 ease-out motion-reduce:transition-none ${chosen ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}>
        <div className="min-h-0 overflow-hidden">
          {chosen && (
            <div key={mode} className="animate-rise">
              <Panel>{mode === 'DISCOVER' ? <DiscoverForm /> : <PromptForm />}</Panel>
            </div>
          )}
        </div>
      </div>

      <details className="mt-6 rounded-2xl border border-[var(--line)] bg-[var(--panel)] px-5 py-4">
        <summary className="cursor-pointer text-[15px] font-medium">I found a job myself, add it from a link</summary>
        <div className="mt-4"><PasteLinkPanel /></div>
      </details>
    </>
  )
}
