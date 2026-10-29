import { Link } from 'react-router-dom'
import { Button } from './Form'

/** What a stage shows when the work before it has not been done: say so plainly and point at what to do first. */
export function NotStarted({ stage, first, to, onGo, toLabel }: {
  stage: string
  first: string
  to?: string
  onGo?: () => void
  toLabel?: string
}) {
  return (
    <section aria-labelledby="not-started" className="mx-auto max-w-xl rounded-xl border border-dashed border-[var(--line-strong)] bg-[var(--panel)] px-8 py-12 text-center">
      <div aria-hidden className="mx-auto mb-4 h-1.5 w-40 overflow-hidden rounded-full bg-[var(--line)]"><div className="h-full w-0 bg-[var(--accent)]" /></div>
      <h1 id="not-started" className="text-lg font-semibold tracking-tight">{stage} has not been started yet</h1>
      <p className="mt-2 text-sm leading-relaxed text-[var(--muted)]">Please complete <strong className="font-semibold text-[var(--ink)]">{first}</strong> first.</p>
      <div className="mt-6">
        {to ? <Link to={to}><Button>{toLabel ?? `Go to ${first}`}</Button></Link> : <Button onClick={onGo}>{toLabel ?? `Go to ${first}`}</Button>}
      </div>
    </section>
  )
}
