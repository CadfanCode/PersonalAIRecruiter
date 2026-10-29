import { type SearchModeChoice, useChooseMode } from '../../api/onboarding'
import { Button } from '../../components/Form'
import { CheckIcon, SearchIcon, SparkIcon } from '../../components/ui/Icons'

export const MODE_CARDS: { id: SearchModeChoice; title: string; text: string; points: string[]; icon: typeof SearchIcon }[] = [
  {
    id: 'DISCOVER',
    title: 'Discovery Mode',
    text: 'AI reads your whole CV and suggests roles you would be a strong match for, in your own field and beyond it.',
    points: ['Each suggestion shows a match score, why, and the obvious gaps', 'You pick which roles to search for', 'Nothing to configure: the search is set up for you'],
    icon: SparkIcon,
  },
  {
    id: 'PROMPT',
    title: 'Prompt Mode',
    text: 'You describe the roles you want and where. JobPilot turns that into a search and shows you the plan first.',
    points: ['Write it like you would tell a friend', 'Recency, place and seniority come from your words', 'Review the search plan before anything runs'],
    icon: SearchIcon,
  },
]

/** Step 4 (and the choice on the Mode page): two cards. Choosing one saves it. */
export function ModeStep({ selected, finishing, onBack, onFinish }: {
  selected: SearchModeChoice | null
  finishing: boolean
  onBack: () => void
  onFinish: () => void
}) {
  const choose = useChooseMode()
  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">How do you want to search?</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">You can switch whenever you start a new search.</p>
      <div role="radiogroup" aria-label="Mode" className="mt-7 grid gap-4 sm:grid-cols-2">
        {MODE_CARDS.map(({ id, title, text, points, icon: Icon }) => (
          <button key={id} type="button" role="radio" aria-checked={selected === id} onClick={() => choose.mutate(id)}
            className={`flex flex-col items-start gap-3 rounded-3xl border p-6 text-left transition ${
              selected === id ? 'border-[var(--accent)] bg-[var(--accent-wash)] shadow-[var(--shadow)]' : 'border-[var(--line)] bg-[var(--panel)] hover:border-[var(--muted)]/50'}`}>
            <span className="flex h-11 w-11 items-center justify-center rounded-full bg-[var(--panel)] text-[var(--accent)] shadow-sm"><Icon /></span>
            <span className="text-lg font-semibold tracking-tight">{title}</span>
            <span className="text-sm leading-relaxed text-[var(--muted)]">{text}</span>
            <ul className="mt-1 flex flex-col gap-1.5 text-sm">
              {points.map((p) => (
                <li key={p} className="flex gap-2"><CheckIcon width={16} height={16} className="mt-0.5 shrink-0 text-[var(--success)]" />{p}</li>
              ))}
            </ul>
          </button>
        ))}
      </div>
      {choose.isError && <p role="alert" className="mt-4 text-sm text-[var(--danger)]">{choose.error.message}</p>}
      <div className="mt-8 flex justify-between">
        <Button variant="ghost" onClick={onBack}>Back</Button>
        <Button size="lg" onClick={onFinish} disabled={!selected || finishing}>{finishing ? 'Finishing…' : 'Finish setup'}</Button>
      </div>
    </div>
  )
}
