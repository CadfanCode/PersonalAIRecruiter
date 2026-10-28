import { useState } from 'react'
import {
  type ProviderId,
  type TestResult,
  useConnection,
  useSaveApiKey,
  useSelectProvider,
  useTestConnection,
} from '../../api/connection'
import { Button, Field, TextInput } from '../../components/Form'
import { CheckIcon, KeyIcon, LockIcon, TerminalIcon, WarnIcon } from '../../components/ui/Icons'
import { Pill } from '../../components/ui/Pill'

const CARDS: { id: ProviderId; title: string; text: string; icon: typeof KeyIcon }[] = [
  { id: 'cli', title: 'Claude Code subscription', text: 'Use the Claude account you already pay for. JobPilot talks to the Claude Code program on this computer.', icon: TerminalIcon },
  { id: 'api', title: 'Anthropic API key', text: 'Paste a key from console.anthropic.com. You pay per use, with no subscription needed.', icon: KeyIcon },
]

/** Step 2: connect the user's own Claude access, test it for real, and only then let them go on. */
export function ConnectStep({ alreadyWorks, onBack, onNext }: { alreadyWorks: boolean; onBack: () => void; onNext: () => void }) {
  const conn = useConnection()
  const select = useSelectProvider()
  const saveKey = useSaveApiKey()
  const test = useTestConnection()
  const [choice, setChoice] = useState<ProviderId>(conn.data?.chosen ? conn.data.selected : 'cli')
  const [key, setKey] = useState('')
  const [result, setResult] = useState<TestResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const status = conn.data
  const busy = select.isPending || saveKey.isPending || test.isPending
  const connected = result?.ok === true || (alreadyWorks && !result && status?.selected === choice)

  const runTest = async (provider: ProviderId) => {
    setError(null)
    try {
      await select.mutateAsync(provider)
      setResult(await test.mutateAsync(provider))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const saveAndTest = async () => {
    setError(null)
    try {
      await saveKey.mutateAsync({ key })
      setKey('')
      await runTest('api')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const pick = (id: ProviderId) => {
    setChoice(id)
    setResult(null)
    setError(null)
  }

  return (
    <div>
      <h1 className="text-3xl font-semibold tracking-tight">Connect Claude</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">
        Claude does the reading, scoring and writing. JobPilot uses your own access, so nothing goes through anyone else.
      </p>

      <div role="radiogroup" aria-label="How to connect" className="mt-7 grid gap-4 sm:grid-cols-2">
        {CARDS.map(({ id, title, text, icon: Icon }) => (
          <button key={id} type="button" role="radio" aria-checked={choice === id} onClick={() => pick(id)}
            className={`flex flex-col items-start gap-2 rounded-3xl border-2 p-5 text-left transition ${
              choice === id ? 'border-[var(--accent)] bg-[var(--accent-soft)] shadow-[var(--shadow)]' : 'border-[var(--line)] bg-[var(--panel)] hover:border-[var(--muted)]/50'}`}>
            <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--panel)] text-[var(--accent)] shadow-sm"><Icon /></span>
            <span className="text-[15px] font-semibold">{title}</span>
            <span className="text-sm leading-relaxed text-[var(--muted)]">{text}</span>
          </button>
        ))}
      </div>

      <div className="mt-6 flex items-start gap-3 rounded-2xl bg-[var(--panel-2)] px-4 py-3 text-sm text-[var(--muted)]">
        <LockIcon className="mt-0.5 shrink-0 text-[var(--accent)]" />
        <p>
          Your sign-in and keys stay on this computer. Keys go into your operating system's secure store
          {status?.secureStore.available ? `: ${status.secureStore.name}` : ''}, never into a plain file.
        </p>
      </div>

      {choice === 'cli' && (
        <div className="mt-6 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-5">
          {!status ? (
            <p className="text-sm text-[var(--muted)]">Looking for Claude Code…</p>
          ) : status.cli.found ? (
            <p className="flex items-center gap-2 text-sm"><Pill tone="success"><CheckIcon width={12} height={12} /> Found</Pill> {status.cli.version}</p>
          ) : (
            <div>
              <p className="flex items-center gap-2 text-sm"><Pill tone="warn"><WarnIcon width={12} height={12} /> Not found</Pill> Claude Code is not installed on this computer yet.</p>
              {status.cli.help && <code className="mt-3 block overflow-x-auto rounded-xl bg-[var(--surface)] px-3 py-2 text-xs">{status.cli.help}</code>}
              <div className="mt-3"><Button variant="ghost" onClick={() => void conn.refetch()}>Check again</Button></div>
            </div>
          )}
          <div className="mt-4">
            <Button onClick={() => void runTest('cli')} disabled={busy || (status ? !status.cli.found : true)}>
              {test.isPending ? 'Testing…' : 'Test the connection'}
            </Button>
          </div>
        </div>
      )}

      {choice === 'api' && (
        <div className="mt-6 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-5">
          {status && !status.secureStore.available ? (
            <p role="alert" className="text-sm text-[var(--danger)]">
              This computer has no secure credential store, so a key cannot be saved safely. On Linux, install and unlock a keyring such as
              GNOME Keyring or KWallet, or use the Claude Code subscription.
            </p>
          ) : (
            <>
              {status?.api.configured && (
                <p className="mb-3 flex items-center gap-2 text-sm"><Pill tone="success"><CheckIcon width={12} height={12} /> Key saved</Pill> {status.api.keyHint}</p>
              )}
              <Field label={status?.api.configured ? 'Replace the saved key' : 'Anthropic API key'}>
                <TextInput type="password" autoComplete="off" spellCheck={false} placeholder="sk-ant-…" aria-label="Anthropic API key"
                  value={key} onChange={(e) => setKey(e.target.value)} />
              </Field>
              <div className="mt-4 flex flex-wrap gap-2">
                <Button onClick={() => void saveAndTest()} disabled={busy || key.trim().length < 20}>
                  {saveKey.isPending || test.isPending ? 'Testing…' : 'Save and test'}
                </Button>
                {status?.api.configured && key === '' && (
                  <Button variant="ghost" onClick={() => void runTest('api')} disabled={busy}>Test the saved key</Button>
                )}
              </div>
            </>
          )}
        </div>
      )}

      {connected && (
        <p role="status" className="mt-5 flex items-center gap-2 rounded-2xl bg-[var(--success-soft)] px-4 py-3 text-sm font-medium text-[var(--success)]">
          <CheckIcon /> Connected. Claude answered.
        </p>
      )}
      {result && !result.ok && (
        <div role="alert" className="mt-5 rounded-2xl bg-[var(--danger-soft)] px-4 py-3 text-sm text-[var(--danger)]">
          <p className="font-medium">{result.message}</p>
          {result.help && <p className="mt-1 text-[var(--ink)]">{result.help}</p>}
        </div>
      )}
      {error && <p role="alert" className="mt-5 rounded-2xl bg-[var(--danger-soft)] px-4 py-3 text-sm text-[var(--danger)]">{error}</p>}

      <div className="mt-8 flex justify-between">
        <Button variant="ghost" onClick={onBack}>Back</Button>
        <Button size="lg" onClick={onNext} disabled={!connected}>Continue</Button>
      </div>
    </div>
  )
}
