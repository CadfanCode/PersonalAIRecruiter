import { useState } from 'react'
import { useSaveLogin } from '../../api/logins'
import { Button, Field, TextInput } from '../Form'
import { LockIcon } from '../ui/Icons'

const declinedKey = (host: string) => `jobpilot.loginDeclined.${host}`

export function loginDeclined(host: string): boolean {
  try {
    return localStorage.getItem(declinedKey(host)) === '1'
  } catch {
    return false
  }
}

/**
 * After the user signed in to a portal themselves: ask whether JobPilot may remember that login. The user types it into
 * JobPilot's own form here, never into the employer's page through JobPilot. It is kept in the computer's secure store.
 */
export function SaveLoginPrompt({ host, onClose }: { host: string; onClose: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const save = useSaveLogin()

  const decline = () => {
    try {
      localStorage.setItem(declinedKey(host), '1')
    } catch {
      // the question may come back; that is harmless
    }
    onClose()
  }

  return (
    <div role="group" aria-label={`Save your login for ${host}`} className="mt-4 rounded-2xl bg-[var(--accent-soft)] p-4 text-sm">
      <p className="flex items-center gap-2 font-semibold"><LockIcon width={16} height={16} className="text-[var(--accent)]" /> Save your login for {host}?</p>
      <p className="mt-1 text-[var(--muted)]">
        Next time JobPilot fills in the username and password for you. You still press Log in yourself. It is stored only in your computer's
        secure credential store, only for this site, and you can delete it in Settings. JobPilot never creates accounts for you.
      </p>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <Field label="Username or email">
          <TextInput aria-label="Username or email" autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} />
        </Field>
        <Field label="Password">
          <TextInput aria-label="Password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
      </div>
      <div className="mt-3 flex gap-2">
        <Button disabled={!username.trim() || !password || save.isPending}
          onClick={() => save.mutate({ host, username, password }, { onSuccess: onClose })}>{save.isPending ? 'Saving…' : 'Save login'}</Button>
        <Button variant="ghost" onClick={decline}>No thanks</Button>
      </div>
      {save.isError && <p role="alert" className="mt-2 text-[var(--danger)]">{save.error.message}</p>}
    </div>
  )
}
