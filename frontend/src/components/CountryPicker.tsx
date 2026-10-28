import { useChooseCountry, useCountry } from '../api/country'
import { CheckIcon, GlobeIcon } from './ui/Icons'
import { Pill } from './ui/Pill'

/** The country list, shared by setup and Settings. Choosing one saves it. Only Sweden has job sources built; the others say so. */
export function CountryPicker() {
  const country = useCountry()
  const choose = useChooseCountry()
  const selected = country.data?.chosen ? country.data.selected : null
  return (
    <>
      <ul role="radiogroup" aria-label="Country" className="grid gap-3 sm:grid-cols-2">
        {country.data?.countries.map((c) => (
          <li key={c.code}>
            <button type="button" role="radio" aria-checked={selected === c.code} disabled={!c.available || choose.isPending}
              onClick={() => choose.mutate(c.code)}
              className={`flex w-full items-center gap-3 rounded-lg border px-4 py-3.5 text-left transition-colors ${
                selected === c.code ? 'border-[var(--accent)] bg-[var(--accent-soft)]' : 'border-[var(--line)] bg-[var(--panel)]'} ${
                c.available ? 'cursor-pointer hover:border-[var(--line-strong)]' : 'cursor-not-allowed opacity-60'}`}>
              <span className="flex h-9 w-9 items-center justify-center rounded-md bg-[var(--hover)] text-[var(--accent)]"><GlobeIcon /></span>
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
    </>
  )
}
