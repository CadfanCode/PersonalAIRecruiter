import { useChooseCountry, useCountry } from '../../api/country'
import { useSearchSettings, useUpdateSearchSettings } from '../../api/settings'
import { Panel } from '../Panel'
import { Pill } from '../ui/Pill'

/** What is searched: the country, and whether employers' own career pages are searched too (slower, off by default). */
export function SearchPanel() {
  const country = useCountry()
  const choose = useChooseCountry()
  const search = useSearchSettings()
  const update = useUpdateSearchSettings()
  return (
    <div className="grid gap-5">
      <Panel title="Country">
        <ul role="radiogroup" aria-label="Country" className="grid gap-2 sm:grid-cols-2">
          {country.data?.countries.map((c) => (
            <li key={c.code}>
              <button type="button" role="radio" aria-checked={country.data?.selected === c.code} disabled={!c.available || choose.isPending} onClick={() => choose.mutate(c.code)}
                className={`flex w-full items-center gap-3 rounded-2xl border-2 px-4 py-3 text-left text-sm transition ${
                  country.data?.selected === c.code ? 'border-[var(--accent)] bg-[var(--accent-soft)]' : 'border-[var(--line)]'} ${c.available ? '' : 'opacity-55'}`}>
                <span className="flex-1 font-medium">{c.name}</span>
                {c.available ? <span className="text-[var(--muted)]">{c.jobBoard}</span> : <Pill>Coming soon</Pill>}
              </button>
            </li>
          ))}
        </ul>
      </Panel>
      <Panel title="Where JobPilot looks">
        <p className="text-sm text-[var(--muted)]">The national job board of your country is always searched.</p>
        <label className="mt-4 flex items-start gap-3 text-sm">
          <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[var(--accent)]" checked={search.data?.careerPages ?? false} disabled={update.isPending}
            onChange={(e) => update.mutate(e.target.checked)} />
          <span>
            <span className="block font-medium">Also search employers' own career pages</span>
            <span className="block text-[var(--muted)]">Finds ads that are not on the job board. It is slower and uses noticeably more of your Claude access, so it is off unless you want it.</span>
          </span>
        </label>
      </Panel>
    </div>
  )
}
