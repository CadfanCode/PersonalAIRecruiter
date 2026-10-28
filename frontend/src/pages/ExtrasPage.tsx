import { Link } from 'react-router-dom'
import { useCountry } from '../api/country'
import { PageTitle } from '../components/Panel'
import { ArchiveIcon } from '../components/ui/Icons'
import { Pill } from '../components/ui/Pill'

/** Features that go beyond the application flow. Today: the activity report for Arbetsförmedlingen (Sweden). */
export function ExtrasPage() {
  const country = useCountry()
  const available = country.data?.activityReport ?? false
  return (
    <>
      <PageTitle subtitle="Extra things JobPilot can do for you once your applications are sent.">Extra features</PageTitle>
      <ul className="grid gap-4 sm:grid-cols-2">
        <li>
          <Link to={available ? '/aktivitet' : '#'} aria-disabled={!available}
            className={`flex h-full flex-col gap-3 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6 shadow-[var(--shadow)] transition ${available ? 'hover:border-[var(--accent)]' : 'pointer-events-none opacity-60'}`}>
            <span className="flex h-11 w-11 items-center justify-center rounded-full bg-[var(--accent-soft)] text-[var(--accent)]"><ArchiveIcon /></span>
            <span className="flex items-center gap-2 text-lg font-semibold tracking-tight">Activity report {!available && <Pill>Sweden only</Pill>}</span>
            <span className="text-sm leading-relaxed text-[var(--muted)]">
              Fills in your aktivitetsrapport on Arbetsförmedlingen with the applications you submitted. You sign in with BankID yourself,
              check every value, and send the monthly report yourself.
            </span>
          </Link>
        </li>
      </ul>
    </>
  )
}
