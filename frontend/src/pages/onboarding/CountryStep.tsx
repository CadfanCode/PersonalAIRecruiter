import { useChooseCountry, useCountry } from '../../api/country'
import { Button } from '../../components/Form'
import { CheckIcon, GlobeIcon } from '../../components/ui/Icons'
import { Pill } from '../../components/ui/Pill'

/** Step 3: where to search. Only Sweden has job sources built; the others are shown as coming soon. */
export function CountryStep({ onBack, onNext }: { onBack: () => void; onNext: () => void }) {
  const country = useCountry()
  const choose = useChooseCountry()
  const selected = country.data?.chosen ? country.data.selected : null

  return (
    <div>
      <h1 className="text-3xl font-semibold tracking-tight">Where do you want to work?</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">
        The country decides which career pages and job boards JobPilot searches.
      </p>
      <ul role="radiogroup" aria-label="Country" className="mt-7 grid gap-3 sm:grid-cols-2">
        {country.data?.countries.map((c) => (
          <li key={c.code}>
            <button type="button" role="radio" aria-checked={selected === c.code} disabled={!c.available || choose.isPending}
              onClick={() => choose.mutate(c.code)}
              className={`flex w-full items-center gap-3 rounded-2xl border-2 px-4 py-4 text-left transition ${
                selected === c.code ? 'border-[var(--accent)] bg-[var(--accent-soft)]' : 'border-[var(--line)] bg-[var(--panel)]'} ${
                c.available ? 'hover:border-[var(--muted)]/50' : 'cursor-not-allowed opacity-60'}`}>
              <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--panel-2)] text-[var(--accent)]"><GlobeIcon /></span>
              <span className="min-w-0 flex-1">
                <span className="block text-[15px] font-semibold">{c.name}</span>
                <span className="block text-sm text-[var(--muted)]">{c.available ? c.jobBoard : 'Not available yet'}</span>
              </span>
              {c.available ? (selected === c.code && <CheckIcon className="text-[var(--accent)]" />) : <Pill>Coming soon</Pill>}
            </button>
          </li>
        ))}
      </ul>
      {choose.isError && <p role="alert" className="mt-4 text-sm text-[var(--danger)]">{choose.error.message}</p>}
      <div className="mt-8 flex justify-between">
        <Button variant="ghost" onClick={onBack}>Back</Button>
        <Button size="lg" onClick={onNext} disabled={!selected}>Continue</Button>
      </div>
    </div>
  )
}
