import { Link } from 'react-router-dom'
import { useReminder } from '../api/aktivitet'
import { BellIcon } from './ui/Icons'

/** Shown on days 1 to 16 of each month: the aktivitetsrapport window (SPEC 10). */
export function ReminderBanner() {
  const reminder = useReminder()
  if (!reminder.data?.active) return null
  return (
    <div role="note" className="mb-6 flex items-start gap-3 rounded-2xl bg-[var(--warn-soft)] px-4 py-3 text-sm text-[var(--ink)]">
      <BellIcon className="mt-0.5 shrink-0 text-[var(--warn)]" />
      <p>
        {reminder.data.message}{' '}
        <Link className="font-medium text-[var(--accent)] underline" to="/aktivitet">Open the aktivitetsrapport log</Link>
      </p>
    </div>
  )
}
