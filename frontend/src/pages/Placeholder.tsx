import { Link } from 'react-router-dom'
import { PageTitle, Panel } from '../components/Panel'

/** Shown for an address that does not exist. */
export function Placeholder({ title, description }: { title: string; milestone?: number; description: string }) {
  return (
    <>
      <PageTitle>{title}</PageTitle>
      <Panel>
        <p className="text-sm text-[var(--muted)]">{description} <Link className="text-[var(--accent)] underline" to="/">Back to Search mode</Link></p>
      </Panel>
    </>
  )
}
