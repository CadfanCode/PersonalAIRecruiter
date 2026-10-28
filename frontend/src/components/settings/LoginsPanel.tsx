import { useDeleteLogin, useLogins } from '../../api/logins'
import { useConnection } from '../../api/connection'
import { Button } from '../Form'
import { Panel } from '../Panel'
import { LockIcon } from '../ui/Icons'

/** The logins you chose to save for employer portals: which sites, and which username. Passwords are never shown. */
export function LoginsPanel() {
  const logins = useLogins()
  const remove = useDeleteLogin()
  const conn = useConnection()
  return (
    <Panel title="Saved logins">
      <p className="mb-4 flex items-start gap-2 text-sm text-[var(--muted)]">
        <LockIcon className="mt-0.5 shrink-0 text-[var(--accent)]" />
        <span>
          When a site needs you to sign in during Apply, you can let JobPilot remember that login. Passwords are kept in your computer's secure store{conn.data?.secureStore.available ? `: ${conn.data.secureStore.name}` : ''}. They are kept only for that one site, and only typed into that site's login form. You still press Log in yourself.
        </span>
      </p>
      {logins.data?.length === 0 && <p className="text-sm text-[var(--muted)]">No saved logins.</p>}
      <ul className="divide-y divide-[var(--line)]">
        {logins.data?.map((l) => (
          <li key={l.host} className="flex flex-wrap items-center gap-3 py-3 text-sm">
            <span className="font-medium">{l.host}</span>
            <span className="text-[var(--muted)]">{l.username}</span>
            {l.lastUsedAt && <span className="text-xs text-[var(--muted)]">last used {new Date(l.lastUsedAt).toLocaleDateString('sv-SE')}</span>}
            <Button variant="danger" className="ml-auto" aria-label={`Delete the login for ${l.host}`} disabled={remove.isPending} onClick={() => remove.mutate(l.host)}>Delete</Button>
          </li>
        ))}
      </ul>
      {remove.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{remove.error.message}</p>}
    </Panel>
  )
}
