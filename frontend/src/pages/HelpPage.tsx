import { Link } from 'react-router-dom'
import { Button } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'
import { openTour } from '../lib/tourStore'

const STAGES = [
  ['Mode', 'Describe the roles you want, or let Claude suggest roles from your whole CV. You approve the search plan first.'],
  ['Search and ranking', 'Ads are fetched and scored against your CV. Sort, filter and tick the ones you want.'],
  ['Documents', 'A CV and cover letter per job, written from facts in your CV database. Edit, ask Claude to adjust, then approve.'],
  ['Apply', 'Each approved application opens in its own browser tab and is filled in. It stops before the end.'],
  ['Submit', 'You press the employer\'s own submit button, then confirm here. A copy of what you sent is kept.'],
] as const

const NEVER = [
  'Press a submit or send button on an employer\'s site. You do.',
  'Create accounts, or handle BankID or any Arbetsförmedlingen login. It waits for you to sign in.',
  'Tick consent, GDPR, terms or "talent pool" boxes. They are left for you.',
  'Invent anything. A CV or letter only contains facts from your CV database.',
  'Submit your monthly activity report. It only saves activities.',
] as const

const ANSWERS = [
  ['Add or update my CV', 'CV database, then the Import tab. Entries you already have are enriched with anything new, never duplicated. Reading runs in the background, so you can keep working.'],
  ['Use a different model for a part of the app', 'Settings, Claude, Models. For example a lighter model for scoring and a stronger one for the CV and cover letter.'],
  ['Change the country', 'Menu, Country.'],
  ['Claude is not found or not signed in', 'Settings, Claude, then Test the connection. It tells you what to do. JobPilot uses your own Claude Code subscription.'],
  ['Where is my data?', 'On this computer only, in your user folder. Saved logins are in your system\'s secure store.'],
] as const

/** Works offline: everything here is part of the app. */
export function HelpPage() {
  return (
    <>
      <PageTitle subtitle="How JobPilot works, and where to change things.">Help</PageTitle>
      <div className="grid max-w-3xl gap-5">
        <Panel title="Take the tour">
          <p className="text-sm text-[var(--muted)]">A one minute walk through the five stages.</p>
          <div className="mt-3"><Button onClick={openTour}>Start the tour</Button></div>
        </Panel>
        <Panel title="The five stages">
          <ol className="grid gap-3">
            {STAGES.map(([name, text], i) => (
              <li key={name} className="flex gap-3">
                <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[var(--accent-soft)] text-[11px] font-semibold text-[var(--accent)]">{i + 1}</span>
                <p className="text-sm"><strong className="font-semibold">{name}.</strong> <span className="text-[var(--muted)]">{text}</span></p>
              </li>
            ))}
          </ol>
        </Panel>
        <Panel title="What JobPilot never does">
          <ul className="grid gap-2 text-sm">
            {NEVER.map((n) => <li key={n} className="flex gap-2"><span aria-hidden className="text-[var(--accent)]">-</span>{n}</li>)}
          </ul>
        </Panel>
        <Panel title="Common questions">
          <dl className="grid gap-4">
            {ANSWERS.map(([q, a]) => (
              <div key={q}><dt className="text-sm font-medium">{q}</dt><dd className="mt-0.5 text-sm text-[var(--muted)]">{a}</dd></div>
            ))}
          </dl>
          <p className="mt-5 text-sm text-[var(--muted)]">More settings are in <Link className="text-[var(--accent)] underline underline-offset-2" to="/settings">Settings</Link>.</p>
        </Panel>
      </div>
    </>
  )
}
