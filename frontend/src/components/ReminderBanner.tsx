import { Link } from 'react-router-dom'
import { useReminder } from '../api/aktivitet'

/** Shown on days 1 to 16 of each month: the aktivitetsrapport window (SPEC 10). */
export function ReminderBanner() {
  const reminder = useReminder()
  if (!reminder.data?.active) return null
  return (
    <div role="note" className="mb-5 max-w-4xl rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100">
      {reminder.data.message}{' '}
      <Link className="font-medium underline" to="/aktivitet">Open the aktivitetsrapport log</Link>
    </div>
  )
}
